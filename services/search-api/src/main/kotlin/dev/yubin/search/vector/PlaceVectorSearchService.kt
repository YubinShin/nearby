package dev.yubin.search.vector

import dev.yubin.search.embed.EmbeddingModel
import dev.yubin.search.observability.QueryMetrics
import dev.yubin.search.query.PlaceHit
import dev.yubin.search.query.QueryLog
import dev.yubin.search.query.SearchRequest
import dev.yubin.search.query.SearchResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.Collections

/**
 * 벡터 검색 채널 — 뜻으로 찾기 (ADR 0007).
 *
 * 키워드 채널([dev.yubin.search.query.PlaceSearchService])과 **응답 모양을 맞춘다.**
 * 6단계에서 둘을 RRF 로 합칠 때(ADR 0003) 두 채널이 같은 계약(순위 + place_id + 점수)을
 * 지켜야 결합이 순수 함수 한 개로 끝나기 때문이다.
 *
 * 두 채널의 성격 차이는 그대로 남는다:
 * - 키워드는 **글자가 겹쳐야** 걸리고, 0건이면 조건을 풀어 재질의한다.
 * - 벡터는 항상 `limit` 개를 돌려준다. "0건"이라는 게 없는 대신, 뜻이 멀어도 뭔가를 준다.
 *   그래서 [minScore] 아래는 잘라낸다 — 안 그러면 아무 말이나 쳐도 그럴싸한 결과가 나온다.
 */
@Service
@ConditionalOnProperty(
	name = ["psp.role.query", "psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class PlaceVectorSearchService(
	private val embeddings: EmbeddingModel,
	private val qdrant: QdrantStore,
	private val metrics: QueryMetrics,
	private val queryLog: QueryLog,
	@Value("\${psp.vector.alias}") private val alias: String,
	@Value("\${psp.vector.min-score}") private val minScore: Float,
	@Value("\${psp.vector.cache-size}") cacheSize: Int,
) {

	/**
	 * 질의 임베딩 캐시. 같은 검색어의 벡터는 몇 번을 만들어도 같으니 다시 계산할 이유가 없다.
	 * 한 프로세스 안에서만 유효한 LRU — 질의 노드가 여러 대가 되면 Redis 로 옮길 자리다
	 * (그때는 노드끼리도 추론을 나눠 아낀다).
	 */
	private val queryVectors: MutableMap<String, FloatArray> = Collections.synchronizedMap(
		object : LinkedHashMap<String, FloatArray>(cacheSize, 0.75f, true) {
			override fun removeEldestEntry(eldest: Map.Entry<String, FloatArray>) = size > cacheSize
		},
	)

	suspend fun search(req: SearchRequest): SearchResponse = metrics.record(CHANNEL) {
		if (req.q.isBlank()) return@record SearchResponse(req.q, 0, req.page, req.size, 0)

		val startedAt = System.nanoTime()
		val vector = embedQuery(req.q)

		// 벡터 엔진에는 페이지 끝까지 달라고 하고(오프셋 개념이 없다) 앱에서 잘라 쓴다.
		val wanted = (req.from + req.size).coerceAtMost(MAX_FETCH)
		val matches = metrics.stage(CHANNEL, "ann") {
			qdrant.query(alias, vector, wanted, PlaceVectors.filter(req))
		}

		val hits = matches.asSequence()
			.filter { it.score >= minScore }
			.drop(req.from)
			.map { toHit(it, req) }
			.toList()

		val tookMs = (System.nanoTime() - startedAt) / 1_000_000
		queryLog.search(req.q, hits.size.toLong(), relaxed = false, tookMs = tookMs, channel = CHANNEL)

		SearchResponse(
			query = req.q,
			// 벡터 검색의 total 은 "코퍼스에 몇 개 있나"가 아니라 "이번에 건져 올린 것 중 몇 개가
			// 문턱을 넘었나"다. 키워드의 total 과 의미가 달라 그대로 비교하면 안 된다.
			total = matches.count { it.score >= minScore }.toLong(),
			page = req.page,
			size = req.size,
			tookMs = tookMs,
			hits = hits,
		)
	}

	private suspend fun embedQuery(q: String): FloatArray {
		queryVectors[q]?.let { return it }
		val vector = metrics.stage(CHANNEL, "embed") { embeddings.embedQuery(q) }
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
			category = m.payload["category_small"] as? String ?: m.payload["category_large"] as? String,
			address = null,   // 벡터 payload 에는 주소를 넣지 않는다 (PlaceVectors.payload 주석 참고)
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

		/** 페이지가 깊어져도 엔진에 무한정 요구하지 않는다 (키워드 채널의 MAX_PAGE 와 같은 취지). */
		private const val MAX_FETCH = 500
	}
}
