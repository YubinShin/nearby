package dev.yubin.search.core.vector

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.util.UUID

/** Qdrant 에 넣을 점 하나 — 벡터 + 나중에 걸러내거나 보여줄 부속 정보(payload). */
data class VectorPoint(val placeId: String, val vector: FloatArray, val payload: Map<String, Any?>) {
	// FloatArray 는 equals/hashCode 가 참조 비교라 data class 기본 구현이 오해를 부른다.
	// 이 클래스는 동등성을 쓸 일이 없으므로 place_id 기준으로 못박아 둔다.
	override fun equals(other: Any?) = other is VectorPoint && other.placeId == placeId
	override fun hashCode() = placeId.hashCode()
}

/** 벡터 검색 결과 한 건. */
data class VectorMatch(val placeId: String, val score: Float, val payload: Map<String, Any?>)

/**
 * Qdrant(벡터 엔진) 접근 계층 (ADR 0007).
 *
 * ### 왜 공식 자바 클라이언트가 아니라 REST 인가
 * 공식 클라이언트는 gRPC(+guava/ListenableFuture) 기반이라 코루틴에 붙이려면 어댑터가 한 겹 더
 * 필요하다. 앱은 이미 WebFlux/코루틴(ADR 0006)이고 여기서 쓰는 기능은 컬렉션 관리·upsert·검색
 * 정도라, REST + WebClient 가 정합도 좋고 **주고받는 JSON 이 그대로 보인다**는 학습상 이점도 크다.
 * 처리량이 문제가 되면 gRPC 로 바꾸는 건 이 클래스 안쪽 교체로 끝난다.
 *
 * ### 인덱스 교체 방식은 ES 와 같다
 * `place_vec_v{n}` 을 새로 만들어 채운 뒤 alias 를 원자적으로 옮긴다 (ADR 0002 의 무중단 스왑).
 * Qdrant 도 alias 를 지원해서 같은 패턴이 그대로 성립한다.
 */
@Component
class QdrantStore(
	@Value("\${psp.qdrant.url}") baseUrl: String,
) {

	private val http = WebClient.builder()
		.baseUrl(baseUrl)
		.codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES) }
		.build()

	// ---- 컬렉션 생명주기 ----

	/** `{alias}_v{n}` 다음 버전 컬렉션을 만든다. 필터로 쓸 필드에는 payload 인덱스도 함께 건다. */
	suspend fun createNextVersion(alias: String, dimension: Int): String {
		val name = "${alias}_v${nextVersion(alias)}"
		http.put().uri("/collections/{name}", name)
			.bodyValue(
				mapOf(
					"vectors" to mapOf("size" to dimension, "distance" to "Cosine"),
					// m: 이웃 연결 수, ef_construct: 만들 때 탐색 폭. 크면 정확하지만 색인이 느려진다.
					"hnsw_config" to mapOf("m" to HNSW_M, "ef_construct" to HNSW_EF_CONSTRUCT),
				),
			)
			.retrieve().awaitBody<Map<String, Any?>>()

		// payload 인덱스가 없으면 필터가 전수 검사로 떨어진다 — 걸 필드는 미리 걸어둔다.
		PAYLOAD_INDEXES.forEach { (field, schema) ->
			http.put().uri("/collections/{name}/index?wait=true", name)
				.bodyValue(mapOf("field_name" to field, "field_schema" to schema))
				.retrieve().awaitBody<Map<String, Any?>>()
		}
		return name
	}

	/** alias 가 지금 가리키는 컬렉션들. (없으면 빈 집합) */
	suspend fun collectionsBehind(alias: String): Set<String> =
		aliases().filter { it.alias_name == alias }.map { it.collection_name }.toSet()

	/**
	 * alias 를 newCollection 으로 **원자적으로** 옮긴다.
	 * Qdrant 는 액션 목록을 한 덩어리로 적용하므로 "가리키는 곳이 없는" 순간이 생기지 않는다.
	 * 밀려난 옛 컬렉션들을 반환한다.
	 */
	suspend fun swapAlias(alias: String, newCollection: String): Set<String> {
		val previous = collectionsBehind(alias)
		val actions = previous.map { mapOf("delete_alias" to mapOf("alias_name" to alias)) } +
			listOf(mapOf("create_alias" to mapOf("collection_name" to newCollection, "alias_name" to alias)))
		http.post().uri("/collections/aliases")
			.bodyValue(mapOf("actions" to actions))
			.retrieve().awaitBody<Map<String, Any?>>()
		return previous - newCollection
	}

	suspend fun deleteCollections(names: Set<String>) {
		names.forEach { http.delete().uri("/collections/{name}", it).retrieve().awaitBody<Map<String, Any?>>() }
	}

	suspend fun count(collection: String): Long =
		http.post().uri("/collections/{name}/points/count", collection)
			.bodyValue(mapOf("exact" to true))
			.retrieve().awaitBody<CountResponse>().result.count

	// ---- 점 넣고 빼기 ----

	/** 같은 place_id 면 덮어쓴다 — 재실행에 안전(멱등, ADR 0001). */
	suspend fun upsert(collection: String, points: List<VectorPoint>) {
		if (points.isEmpty()) return
		http.put().uri("/collections/{name}/points?wait=true", collection)
			.bodyValue(
				mapOf(
					"points" to points.map {
						mapOf("id" to pointId(it.placeId), "vector" to it.vector.toList(), "payload" to it.payload)
					},
				),
			)
			.retrieve().awaitBody<Map<String, Any?>>()
	}

	/** 없는 점을 지우는 것도 성공으로 친다(멱등). */
	suspend fun delete(collection: String, placeIds: List<String>) {
		if (placeIds.isEmpty()) return
		http.post().uri("/collections/{name}/points/delete?wait=true", collection)
			.bodyValue(mapOf("points" to placeIds.map { pointId(it) }))
			.retrieve().awaitBody<Map<String, Any?>>()
	}

	// ---- 검색 ----

	suspend fun query(
		collection: String,
		vector: FloatArray,
		limit: Int,
		filter: Map<String, Any?>? = null,
		efSearch: Int = HNSW_EF_SEARCH,
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
			val placeId = p.payload["place_id"] as? String ?: return@mapNotNull null
			VectorMatch(placeId, p.score, p.payload)
		}
	}

	// ---- 내부 ----

	/**
	 * alias 조회는 `GET /aliases`, alias 변경은 `POST /collections/aliases` 로 **경로가 다르다.**
	 * (조회를 `/collections/aliases` 로 부르면 404 — 실제로 이 실수로 8분짜리 적재를 날렸다.)
	 */
	private suspend fun aliases(): List<AliasDescription> =
		http.get().uri("/aliases").retrieve().awaitBody<AliasesResponse>().result.aliases

	private suspend fun nextVersion(alias: String): Int {
		val versioned = Regex("^${Regex.escape(alias)}_v(\\d+)$")
		val max = http.get().uri("/collections").retrieve()
			.awaitBody<CollectionsResponse>().result.collections
			.mapNotNull { versioned.find(it.name)?.groupValues?.get(1)?.toInt() }
			.maxOrNull() ?: 0
		return max + 1
	}

	companion object {
		/**
		 * Qdrant 의 점 id 는 **부호 없는 정수이거나 UUID** 여야 한다. 우리 place_id 는 문자열이라
		 * 그대로 못 쓴다. 그래서 place_id 로부터 **결정적으로** UUID 를 만든다 —
		 * 같은 place_id 는 언제 어디서 계산해도 같은 id 가 되어야 upsert/delete 가 멱등해진다.
		 * (원본 place_id 는 payload 에 그대로 담아 되돌릴 수 있게 한다.)
		 */
		fun pointId(placeId: String): String =
			UUID.nameUUIDFromBytes(placeId.toByteArray(Charsets.UTF_8)).toString()

		private const val HNSW_M = 16
		private const val HNSW_EF_CONSTRUCT = 100
		private const val HNSW_EF_SEARCH = 128
		private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

		/** 필터로 쓸 payload 필드 → Qdrant 스키마. */
		private val PAYLOAD_INDEXES = mapOf(
			"sigungu" to "keyword",
			"dong" to "keyword",
			"category_large" to "keyword",
			"location" to "geo",
		)
	}
}

// ---- 응답 매핑 (필요한 필드만) ----

internal data class AliasesResponse(val result: AliasList)
internal data class AliasList(val aliases: List<AliasDescription>)
internal data class AliasDescription(val alias_name: String, val collection_name: String)
internal data class CollectionsResponse(val result: CollectionList)
internal data class CollectionList(val collections: List<CollectionDescription>)
internal data class CollectionDescription(val name: String)
internal data class CountResponse(val result: CountResult)
internal data class CountResult(val count: Long)
internal data class QueryResponse(val result: QueryResult)
internal data class QueryResult(val points: List<ScoredPoint>)
internal data class ScoredPoint(val id: String, val score: Float, val payload: Map<String, Any?>)
