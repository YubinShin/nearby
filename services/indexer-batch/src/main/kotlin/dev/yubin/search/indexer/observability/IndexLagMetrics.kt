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
	@Value("\${psp.vector.enabled}") vectorEnabled: Boolean,
) {
	private val lags: Map<String, AtomicLong> = buildMap {
		put(CheckpointStore.PLACE_PIPELINE, AtomicLong(NO_CHECKPOINT))
		if (vectorEnabled) put(CheckpointStore.PLACE_VECTOR, AtomicLong(NO_CHECKPOINT))
	}

	init {
		lags.forEach { (pipeline, value) ->
			Gauge.builder(METRIC) { value.get().toDouble() }
				.tag("pipeline", TAGS.getValue(pipeline))
				.description("seconds between the newest source change and the index checkpoint. 0 means caught up, -1 means no checkpoint")
				.baseUnit("seconds")
				.register(registry)
		}
	}

	@Scheduled(fixedDelayString = "\${psp.metrics.lag-refresh-ms}", initialDelayString = "5000")
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
			log.warn("failed to refresh index lag metrics: {}", e.message)
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