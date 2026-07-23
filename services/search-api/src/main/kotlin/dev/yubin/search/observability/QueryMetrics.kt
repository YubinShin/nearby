package dev.yubin.search.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 질의 지연/실패를 채널별로 기록한다 (아키텍처 크리틱 #8 — 관측성).
 *
 * 채널(keyword/suggest)마다 따로 재는 게 핵심이다. 합쳐서 재면 "검색이 느리다"까지만 알고
 * *어디가* 느린지를 모른다. 5단계에서 벡터 채널이 붙으면 태그 하나만 늘어난다.
 * `/actuator/prometheus` 로 노출된다.
 */
@Component
class QueryMetrics(private val registry: MeterRegistry) {

	suspend fun <T> record(channel: String, block: suspend () -> T): T {
		val startedAt = System.nanoTime()
		var outcome = "success"
		try {
			return block()
		} catch (e: Throwable) {
			outcome = "error"
			throw e
		} finally {
			registry.timer("psp.query.latency", "channel", channel, "outcome", outcome)
				.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
		}
	}
}
