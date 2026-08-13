package dev.yubin.search.vector

import dev.yubin.search.observability.QueryMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbedGateTest {
	@Test
	fun `a request that fits the permits runs without entering the queue`() = runBlocking {
		val registry = SimpleMeterRegistry()
		val gate = gate(registry, permits = 2, maxQueue = 0)

		assertEquals("ok", gate.withPermit { "ok" })
		assertEquals(0L, registry.find("psp.query.embed.wait").timer()?.count() ?: 0L)
	}

	@Test
	fun `waiting past the timeout is rejected instead of queueing without end`() = runBlocking {
		val registry = SimpleMeterRegistry()
		val gate = gate(registry, permits = 1, maxQueue = 8, waitTimeout = Duration.ofMillis(50))

		holdingThePermit(gate) {
			val rejected = assertFailsWith<EmbedOverloadException> { gate.withPermit { "never" } }

			assertEquals(EmbedGate.TIMEOUT, rejected.reason)
			assertEquals(1.0, registry.get("psp.query.embed.rejected").tag("reason", EmbedGate.TIMEOUT).counter().count())
		}
	}

	@Test
	fun `a request arriving with the queue already full is rejected without waiting`() = runBlocking {
		val registry = SimpleMeterRegistry()
		val gate = gate(registry, permits = 1, maxQueue = 0, waitTimeout = Duration.ofSeconds(30))

		holdingThePermit(gate) {
			val startedAt = System.nanoTime()
			val rejected = assertFailsWith<EmbedOverloadException> { gate.withPermit { "never" } }
			val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

			assertEquals(EmbedGate.QUEUE_FULL, rejected.reason)
			assertTrue(elapsedMs < 1_000, "the depth check must reject before the wait, but it took ${elapsedMs}ms")
			assertEquals(1.0, registry.get("psp.query.embed.rejected").tag("reason", EmbedGate.QUEUE_FULL).counter().count())
		}
	}

	@Test
	fun `a permit is released when the guarded block throws`() = runBlocking {
		val gate = gate(SimpleMeterRegistry(), permits = 1, maxQueue = 0)

		assertFailsWith<IllegalStateException> { gate.withPermit { throw IllegalStateException("inference blew up") } }

		assertEquals("ok", gate.withPermit { "ok" })
	}

	@Test
	fun `a rejected request does not consume a permit`() = runBlocking {
		val gate = gate(SimpleMeterRegistry(), permits = 1, maxQueue = 0, waitTimeout = Duration.ofMillis(50))

		holdingThePermit(gate) {
			assertFailsWith<EmbedOverloadException> { gate.withPermit { "never" } }
		}

		assertEquals("ok", gate.withPermit { "ok" })
	}

	@Test
	fun `a timeout that races with the handover does not strand the permit`() = runBlocking {
		val gate = gate(SimpleMeterRegistry(), permits = 2, maxQueue = 1_000, waitTimeout = Duration.ofMillis(2))

		coroutineScope {
			repeat(400) {
				launch(Dispatchers.Default) {
					try {
						gate.withPermit { delay(1) }
					} catch (_: EmbedOverloadException) {
					}
				}
			}
		}

		coroutineScope {
			repeat(2) {
				launch(Dispatchers.Default) { gate.withPermit { delay(50) } }
			}
		}
	}

	private suspend fun holdingThePermit(gate: EmbedGate, block: suspend () -> Unit) {
		val acquired = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		val holder = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
			gate.withPermit {
				acquired.complete(Unit)
				release.await()
			}
		}
		acquired.await()
		try {
			block()
		} finally {
			release.complete(Unit)
			holder.join()
		}
	}

	private fun gate(
		registry: MeterRegistry,
		permits: Int,
		maxQueue: Int,
		waitTimeout: Duration = Duration.ofMillis(50),
	) = EmbedGate(
		poolSize = permits,
		maxQueue = maxQueue,
		waitTimeout = waitTimeout,
		metrics = QueryMetrics(registry),
	)
}
