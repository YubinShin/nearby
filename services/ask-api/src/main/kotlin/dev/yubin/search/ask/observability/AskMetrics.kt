package dev.yubin.search.ask.observability

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class AskMetrics(private val registry: MeterRegistry) {
	suspend fun <T> record(stage: String, block: suspend () -> T): T {
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
			registry.timer("psp.ask.latency", "stage", stage, "outcome", outcome)
				.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
		}
	}

	fun degraded(stage: String, reason: String) {
		registry.counter("psp.ask.degraded", "stage", stage, "reason", reason).increment()
	}
}
