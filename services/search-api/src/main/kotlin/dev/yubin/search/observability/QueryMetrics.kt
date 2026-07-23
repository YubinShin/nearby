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

	/**
	 * 한 채널 **안에서** 단계별로 쪼개 잰다 (벡터 채널: 임베딩 추론 vs 벡터 탐색).
	 *
	 * 채널 지연만 보면 "벡터 검색이 느리다"까지만 알 수 있다. 임베딩과 탐색은 고치는 방법이
	 * 완전히 다르므로(모델 교체·캐시 vs ef/샤딩) 처음부터 갈라 재둔다.
	 */
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
}
