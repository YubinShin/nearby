package dev.yubin.search.vector

import dev.yubin.search.core.embed.EmbeddingModel
import dev.yubin.search.core.vector.VectorMatch
import dev.yubin.search.observability.QueryMetrics
import dev.yubin.search.query.PlaceHit
import dev.yubin.search.query.QueryLog
import dev.yubin.search.query.SearchRequest
import dev.yubin.search.query.SearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.Collections

@Service
@ConditionalOnProperty(
	name = ["psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class PlaceVectorSearchService(
	private val embeddings: EmbeddingModel,
	private val qdrant: QdrantSearchStore,
	private val metrics: QueryMetrics,
	private val queryLog: QueryLog,
	@Value("\${psp.vector.alias}") private val alias: String,
	@Value("\${psp.vector.min-score}") private val minScore: Float,
	@Value("\${psp.vector.cache-size}") cacheSize: Int,
) {
	private val queryVectors: MutableMap<String, FloatArray> = Collections.synchronizedMap(
		object : LinkedHashMap<String, FloatArray>(cacheSize, 0.75f, true) {
			override fun removeEldestEntry(eldest: Map.Entry<String, FloatArray>) = size > cacheSize
		},
	)

	private val embedDispatcher = Dispatchers.Default.limitedParallelism(embeddings.poolSize)

	suspend fun search(req: SearchRequest): SearchResponse = metrics.record(CHANNEL) {
		if (req.q.isBlank()) return@record SearchResponse(req.q, 0, req.page, req.size, 0)

		val startedAt = System.nanoTime()
		val vector = embedQuery(req.q)
		val filter = PlaceVectors.filter(req)

		val wanted = (req.from + req.size).coerceAtLeast(MIN_CANDIDATES).coerceAtMost(MAX_FETCH)
		val matches = metrics.stage(CHANNEL, "ann") { qdrant.query(alias, vector, wanted, filter) }

		val narrowed = filter != null && narrowsCandidates(filter)
		val floor = if (narrowed) NO_FLOOR else minScore
		val passed = matches.filter { it.score >= floor }

		val hits = passed.drop(req.from).take(req.size).map { toHit(it, req) }

		val tookMs = (System.nanoTime() - startedAt) / 1_000_000
		queryLog.search(req.q, hits.size.toLong(), relaxed = false, tookMs = tookMs, channel = CHANNEL)

		SearchResponse(
			query = req.q,

			total = passed.size.toLong(),
			page = req.page,
			size = req.size,
			tookMs = tookMs,
			hits = hits,
		)
	}

	private suspend fun narrowsCandidates(filter: Map<String, Any?>): Boolean =
		metrics.stage(CHANNEL, "narrow") { qdrant.narrows(alias, filter) }

	private suspend fun embedQuery(q: String): FloatArray {
		queryVectors[q]?.let { return it }
		val vector = metrics.stage(CHANNEL, "embed") {
			withContext(embedDispatcher) { embeddings.embedQuery(q) }
		}
		queryVectors[q] = vector
		return vector
	}

	private fun toHit(m: VectorMatch, req: SearchRequest): PlaceHit {
		val location = m.payload["location"] as? Map<*, *>
		val lat = (location?.get("lat") as? Number)?.toDouble()
		val lon = (location?.get("lon") as? Number)?.toDouble()
		return PlaceHit(
			placeId = m.placeId,
			name = m.payload["name"] as? String ?: "",
			branch = m.payload["branch"] as? String,
			brand = m.payload["brand"] as? String,
			category = m.payload["category_small"] as? String ?: m.payload["category_large"] as? String,
			address = null,
			sigungu = m.payload["sigungu"] as? String,
			dong = m.payload["dong"] as? String,
			lat = lat,
			lon = lon,
			score = m.score.toDouble(),
			distanceM = if (req.hasGeo && lat != null && lon != null) {
				PlaceVectors.distanceM(req.lat!!, req.lon!!, lat, lon)
			} else {
				null
			},
		)
	}

	companion object {
		const val CHANNEL = "vector"

		private const val MAX_FETCH = 500

		private const val MIN_CANDIDATES = 50

		private const val NO_FLOOR = Float.NEGATIVE_INFINITY
	}
}
