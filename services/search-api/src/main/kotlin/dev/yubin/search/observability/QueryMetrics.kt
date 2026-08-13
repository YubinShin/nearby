package dev.yubin.search.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Component
class QueryMetrics(private val registry: MeterRegistry) {
	suspend fun <T> record(channel: String, block: suspend () -> T): T {
		val startedAt = System.nanoTime()
		var outcome = "success"
		try {
			return block()
		} catch (e: CancellationException) {
			outcome = "cancelled"
			throw e
		} catch (e: Throwable) {
			outcome = "error"
			throw e
		} finally {
			registry.timer("psp.query.latency", "channel", channel, "outcome", outcome)
				.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
		}
	}

	final inline fun <T> stage(channel: String, stage: String, block: () -> T): T {
		val startedAt = System.nanoTime()
		try {
			return block()
		} finally {
			timer(channel, stage).record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
		}
	}

	fun timer(channel: String, stage: String) =
		registry.timer("psp.query.stage.latency", "channel", channel, "stage", stage)

	fun staleVectors(count: Int) =
		registry.counter("psp.query.stale.vectors").increment(count.toDouble())

	fun embedWaited(nanos: Long) =
		registry.timer("psp.query.embed.wait").record(nanos, TimeUnit.NANOSECONDS)

	fun embedRejected(reason: String) =
		registry.counter("psp.query.embed.rejected", "reason", reason).increment()

	fun embedQueueDepth(queued: AtomicInteger): Gauge =
		Gauge.builder("psp.query.embed.queue.depth", queued) { it.get().toDouble() }.register(registry)
}
