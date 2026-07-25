package dev.yubin.search.vector

import dev.yubin.search.core.vector.QdrantContract
import dev.yubin.search.core.vector.VectorMatch
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

/**
 * Qdrant **질의** 전용 클라이언트 (ADR 0007 · 0013).
 *
 * ### 왜 core 가 아니라 여기 있나
 * 전에는 `search-core.QdrantStore` 하나가 색인기와 질의기를 다 담당했다. 그런데 그 클래스가
 * `WebClient` 를 쓰는 바람에 **동시성이 1인 배치 색인기까지 WebFlux 를 클래스패스에 짊어졌다.**
 *
 * 그래서 호출 방법을 앱별로 갈랐다 (ADR 0013):
 * - 여기(`search-api`) — **WebClient + suspend.** 동시 질의가 많고 저지연이 전부라 리액티브가
 *   실제로 값을 한다 (ADR 0006).
 * - `indexer-batch.QdrantIndexStore` — **RestClient + 블로킹.** 한 번에 job 하나다.
 *
 * 중복이 거의 없다: 질의기는 `query` 하나만 쓰고, 색인기는 컬렉션 생명주기와 upsert/delete 만
 * 쓴다. 둘이 **반드시 같아야 하는 규칙**(점 id 계산·HNSW 파라미터·payload 인덱스)은 core 의
 * [QdrantContract] 에 남아 있다.
 *
 * ### 왜 공식 자바 클라이언트가 아니라 REST 인가
 * 공식 클라이언트는 gRPC(+guava/ListenableFuture) 기반이라 코루틴에 붙이려면 어댑터가 한 겹 더
 * 필요하다. 질의기는 이미 WebFlux/코루틴(ADR 0006)이고 여기서 쓰는 기능은 검색 하나라,
 * REST + WebClient 가 정합도 좋고 **주고받는 JSON 이 그대로 보인다**는 학습상 이점도 크다.
 */
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

	/**
	 * alias(또는 컬렉션)에 벡터로 최근접 이웃을 묻는다.
	 *
	 * alias 를 그대로 넘긴다 — 어느 버전 컬렉션이 뒤에 있는지는 질의기가 알 필요가 없다. 색인기가
	 * 원자적으로 스왑하므로(ADR 0002) 질의기 입장에서는 이름 하나만 계속 부르면 된다.
	 */
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
			// 탐색 폭. 키우면 정확(recall)해지고 느려진다 — 질의 시점에 조절 가능한 손잡이.
			put("params", mapOf("hnsw_ef" to efSearch))
			filter?.let { put("filter", it) }
		}
		val resp = http.post().uri("/collections/{name}/points/query", collection)
			.bodyValue(body)
			.retrieve().awaitBody<QueryResponse>()

		return resp.result.points.mapNotNull { p ->
			// place_id 가 payload 에 없으면 우리가 넣은 점이 아니다 — 조용히 건너뛴다.
			val placeId = p.payload["place_id"] as? String ?: return@mapNotNull null
			VectorMatch(placeId, p.score, p.payload)
		}
	}

	private companion object {
		const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
	}
}

// ---- 응답 매핑 (필요한 필드만) ----

internal data class QueryResponse(val result: QueryResult)
internal data class QueryResult(val points: List<ScoredPoint>)
internal data class ScoredPoint(val id: String, val score: Float, val payload: Map<String, Any?>)
