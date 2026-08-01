package dev.yubin.search.vector

import dev.yubin.search.core.vector.QdrantContract
import dev.yubin.search.core.vector.VectorMatch
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Component
@ConditionalOnProperty(
	name = ["psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class QdrantSearchStore(
	@Value("\${psp.qdrant.url}") baseUrl: String,
) {
	private val http = WebClient.builder()
		.baseUrl(baseUrl)
		.codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES) }
		.build()

	suspend fun query(
		collection: String,
		vector: FloatArray,
		limit: Int,
		filter: Map<String, Any?>? = null,
		efSearch: Int = QdrantContract.HNSW_EF_SEARCH,
	): List<VectorMatch> {
		val body = buildMap {
			put("query", vector.toList())
			put("limit", limit)
			put("with_payload", true)

			put("params", mapOf("hnsw_ef" to efSearch))
			filter?.let { put("filter", it) }
		}
		val resp = http.post().uri("/collections/{name}/points/query", collection)
			.bodyValue(body)
			.retrieve().awaitBody<QueryResponse>()

		return resp.result.points.mapNotNull { p ->

			val placeId = p.payload["place_id"] as? String ?: return@mapNotNull null
			VectorMatch(placeId, p.score, p.payload)
		}
	}

	suspend fun narrows(collection: String, filter: Map<String, Any?>): Boolean {
		val body = mapOf(
			"filter" to mapOf("must_not" to listOf(filter)),
			"limit" to 1,
			"with_payload" to false,
			"with_vector" to false,
		)
		val resp = http.post().uri("/collections/{name}/points/scroll", collection)
			.bodyValue(body)
			.retrieve().awaitBody<ScrollResponse>()

		return resp.result.points.isNotEmpty()
	}

	private companion object {
		const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
	}
}

internal data class ScrollResponse(val result: ScrollResult)
internal data class ScrollResult(val points: List<Map<String, Any?>>)

internal data class QueryResponse(val result: QueryResult)
internal data class QueryResult(val points: List<ScoredPoint>)
internal data class ScoredPoint(val id: String, val score: Float, val payload: Map<String, Any?>)
