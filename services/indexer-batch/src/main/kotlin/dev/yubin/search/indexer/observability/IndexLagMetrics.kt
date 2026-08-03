package dev.yubin.search.indexer.observability

import dev.yubin.search.indexer.index.CheckpointStore
import dev.yubin.search.indexer.index.PlaceSourceDao
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

@Component
class IndexLagMetrics(
	registry: MeterRegistry,
	private val checkpoints: CheckpointStore,
	private val source: PlaceSourceDao,
	@Value("\${psp.vector.enabled:true}") vectorEnabled: Boolean,
) {
	private val lags: Map<String, AtomicLong> = buildMap {
		put(CheckpointStore.PLACE_PIPELINE, AtomicLong(NO_CHECKPOINT))
		if (vectorEnabled) put(CheckpointStore.PLACE_VECTOR, AtomicLong(NO_CHECKPOINT))
	}

	init {
		lags.forEach { (pipeline, value) ->
			Gauge.builder(METRIC) { value.get().toDouble() }
				.tag("pipeline", TAGS.getValue(pipeline))
				.description("원천 최신 변경 시각과 색인 체크포인트의 차이(초). 0 이면 따라잡음, -1 이면 체크포인트 없음")
				.baseUnit("seconds")
				.register(registry)
		}
	}

	@Scheduled(fixedDelayString = "\${psp.metrics.lag-refresh-ms:10000}", initialDelayString = "5000")
	fun refresh() {
		try {
			val sourceLatest = source.maxUpdatedAt()
			lags.forEach { (pipeline, value) ->
				val checkpoint = checkpoints.get(pipeline)
				value.set(
					when {
						checkpoint == null -> NO_CHECKPOINT
						sourceLatest == null -> 0L

						else -> Duration.between(checkpoint, sourceLatest).seconds.coerceAtLeast(0)
					},
				)
			}
		} catch (e: Exception) {
			log.warn("색인 lag 지표 갱신 실패: {}", e.message)
		}
	}

	companion object {
		private const val METRIC = "psp.index.lag.seconds"
		private const val NO_CHECKPOINT = -1L

		private val TAGS = mapOf(
			CheckpointStore.PLACE_PIPELINE to "keyword",
			CheckpointStore.PLACE_VECTOR to "vector",
		)

		private val log = LoggerFactory.getLogger(IndexLagMetrics::class.java)
	}
}