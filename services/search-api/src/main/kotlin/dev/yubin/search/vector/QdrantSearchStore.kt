package dev.yubin.search.vector

import dev.yubin.search.core.vector.QdrantContract
import dev.yubin.search.core.vector.VectorMatch
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

@Component
@ConditionalOnProperty(
	name = ["psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class QdrantSearchStore(
	@Value("\${psp.qdrant.url}") baseUrl: String,
	@Value("\${psp.qdrant.query-timeout-ms}") queryTimeoutMs: Long,
) {
	private val connections = ConnectionProvider.builder("qdrant")
		.maxConnections(MAX_CONNECTIONS)
		.pendingAcquireMaxCount(PENDING_MAX)
		.pendingAcquireTimeout(ACQUIRE_TIMEOUT)
		.maxIdleTime(MAX_IDLE)
		.maxLifeTime(MAX_LIFE)
		.evictInBackground(EVICT_INTERVAL)
		.build()

	private val http = WebClient.builder()
		.baseUrl(baseUrl)
		.clientConnector(
			ReactorClientHttpConnector(
				HttpClient.create(connections).responseTimeout(Duration.ofMillis(queryTimeoutMs)),
			),
		)
		.codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES) }
		.build()

	@PreDestroy
	fun close() {
		connections.disposeLater().block(DISPOSE_TIMEOUT)
	}

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
		const val MAX_CONNECTIONS = 100
		const val PENDING_MAX = 1000

		val ACQUIRE_TIMEOUT: Duration = Duration.ofSeconds(5)
		val MAX_IDLE: Duration = Duration.ofSeconds(20)
		val MAX_LIFE: Duration = Duration.ofMinutes(5)
		val EVICT_INTERVAL: Duration = Duration.ofSeconds(30)
		val DISPOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
	}
}

internal data class ScrollResponse(val result: ScrollResult)
internal data class ScrollResult(val points: List<Map<String, Any?>>)

internal data class QueryResponse(val result: QueryResult)
internal data class QueryResult(val points: List<ScoredPoint>)
internal data class ScoredPoint(val id: String, val score: Float, val payload: Map<String, Any?>)
