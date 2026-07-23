package dev.yubin.search.observability

import dev.yubin.search.index.CheckpointStore
import dev.yubin.search.index.PlaceR2dbcReader
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * **색인 지연(lag)** — 원천의 가장 최신 변경과, 색인이 따라잡은 지점(체크포인트)의 시간 차이(초).
 *
 * 정의를 고르는 데 실측이 필요했다. 처음엔 `지금 − 체크포인트`로 쟀는데, 원천이 하루 동안
 * 안 바뀌자 lag 이 71,908초로 치솟았다 — **완벽히 최신인데 20시간 뒤처진 것처럼** 보였다.
 * 이 지표로 알림을 걸었다면 매일 밤 오탐이 울린다. 그래서 `원천 최신 − 체크포인트`로 바꿨다:
 * 원천이 조용하면 0, 색인이 밀리는 만큼만 커진다.
 *
 * 검색 플랫폼에서 이게 중요한 이유: 질의는 200 OK 인데 데이터만 낡은 장애는 지표 없이는
 * 조용히 지나간다. "왜 방금 고친 가게가 검색에 안 나오죠?"를 추측이 아니라 숫자로 답하기 위한 것
 * (아키텍처 크리틱 #8).
 *
 * 값 -1 은 "아직 체크포인트 없음"(전체 재색인 전).
 */
@Component
@ConditionalOnProperty(prefix = "psp.role", name = ["indexer"], havingValue = "true", matchIfMissing = true)
class IndexLagMetrics(
	registry: MeterRegistry,
	private val checkpoints: CheckpointStore,
	private val reader: PlaceR2dbcReader,
) {
	private val lagSeconds = AtomicLong(NO_CHECKPOINT)

	init {
		Gauge.builder(METRIC) { lagSeconds.get().toDouble() }
			.description("원천 최신 변경 시각과 색인 체크포인트의 차이(초). 0 이면 따라잡음, -1 이면 체크포인트 없음")
			.baseUnit("seconds")
			.register(registry)
	}

	/** 게이지는 당겨오는(pull) 방식이라 값이 항상 준비돼 있어야 한다 → 주기적으로 미리 읽어 둔다. */
	@Scheduled(fixedDelayString = "\${psp.metrics.lag-refresh-ms:10000}", initialDelayString = "5000")
	fun refresh() {
		try {
			val (checkpoint, sourceLatest) = runBlocking {
				checkpoints.get(CheckpointStore.PLACE_PIPELINE) to reader.maxUpdatedAt()
			}
			lagSeconds.set(
				when {
					checkpoint == null -> NO_CHECKPOINT
					sourceLatest == null -> 0L // 원천이 비었으면 뒤처질 것도 없다
					// 음수(체크포인트가 원천보다 미래)는 정의상 '따라잡음'이다.
					else -> Duration.between(checkpoint, sourceLatest).seconds.coerceAtLeast(0)
				},
			)
		} catch (e: Exception) {
			// 지표 수집 실패가 서비스에 영향을 주면 안 된다 — 로그만 남기고 직전 값을 유지한다.
			log.warn("색인 lag 지표 갱신 실패: {}", e.message)
		}
	}

	companion object {
		private const val METRIC = "psp.index.lag.seconds"
		private const val NO_CHECKPOINT = -1L
		private val log = LoggerFactory.getLogger(IndexLagMetrics::class.java)
	}
}
