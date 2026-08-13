package dev.yubin.search.vector

import dev.yubin.search.observability.QueryMetrics
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@Component
@ConditionalOnProperty(
	name = ["psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class EmbedGate(
	@Value("\${psp.embedding.pool-size}") poolSize: Int,
	@Value("\${psp.embedding.gate.max-queue}") private val maxQueue: Int,
	@Value("\${psp.embedding.gate.wait-timeout}") waitTimeout: Duration,
	private val metrics: QueryMetrics,
) {
	private val permits = Semaphore(poolSize)

	private val queued = AtomicInteger()

	private val waitTimeoutMs = waitTimeout.toMillis()

	init {
		metrics.embedQueueDepth(queued)
	}

	suspend fun <T> withPermit(block: suspend () -> T): T {
		acquire()
		try {
			return block()
		} finally {
			permits.release()
		}
	}

	private suspend fun acquire() {
		if (permits.tryAcquire()) return

		val depth = queued.incrementAndGet()
		try {
			if (depth > maxQueue) reject(QUEUE_FULL, depth)

			val startedAt = System.nanoTime()
			val admitted = withTimeoutOrNull(waitTimeoutMs) { permits.acquire() }
			metrics.embedWaited(System.nanoTime() - startedAt)

			if (admitted == null) reject(TIMEOUT, depth)
		} finally {
			queued.decrementAndGet()
		}
	}

	private fun reject(reason: String, depth: Int): Nothing {
		metrics.embedRejected(reason)
		throw EmbedOverloadException(reason, depth)
	}

	companion object {
		const val QUEUE_FULL = "queue_full"

		const val TIMEOUT = "timeout"
	}
}

class EmbedOverloadException(val reason: String, val queueDepth: Int) :
	RuntimeException("query embedding rejected — $reason (queued $queueDepth)")
