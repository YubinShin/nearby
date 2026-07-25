package dev.yubin.search.indexer.vector

import dev.yubin.search.core.index.IndexVersion
import dev.yubin.search.core.vector.QdrantContract
import dev.yubin.search.core.vector.VectorPoint
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.requiredBody

/**
 * 색인기 쪽 Qdrant 접근 계층 — **컬렉션을 만들고 채우고 갈아치우는 일**만 한다 (ADR 0007, 0013).
 *
 * ### 왜 질의기와 같은 클래스를 안 쓰나
 * 전에는 `search-core` 의 `QdrantStore` 하나를 두 앱이 같이 썼다. 그런데 그 클래스가 `WebClient`
 * 를 쓰는 바람에 core 가 `api("...webflux")` 를 걸어야 했고, 결과적으로 **색인기에도 리액티브
 * 스택이 딸려 왔다.** 색인기는 한 번에 job 하나만 돌리므로 리액티브의 이득(동시 연결 많을 때
 * 메모리)은 없고 비용(요청당 CPU, 디버깅, 취소 경로 누수)만 냈다.
 *
 * 그래서 **계약**(`QdrantContract`: point id 계산법·HNSW 파라미터·payload 인덱스)만 core 에 남기고,
 * **HTTP 호출 방법**은 각 앱이 고른다. 여기는 `RestClient`(블로킹), 질의기는 `WebClient`(코루틴).
 *
 * 중복이 걱정만큼 크지 않은 이유는 **두 앱이 쓰는 기능이 거의 겹치지 않기** 때문이다.
 * 질의기는 `query` 하나만 쓰고, 색인기는 `query` 를 한 번도 안 쓴다. 진짜로 어긋나면 안 되는 것
 * (같은 place_id → 같은 point id, 같은 거리 함수, 같은 payload 인덱스)은 전부 `QdrantContract`
 * 안에 있고, 그게 어긋나면 조용히 결과가 나빠지는 유일한 지점이다.
 *
 * ### 인덱스 교체 방식은 ES 와 같다
 * `place_vec_{yyyyMMddHHmmss}` 을 새로 만들어 채운 뒤 alias 를 원자적으로 옮긴다 (ADR 0002 의
 * 무중단 스왑). Qdrant 도 alias 를 지원해서 같은 패턴이 그대로 성립한다.
 *
 * ### 블로킹이어도 되는 이유
 * 이 클래스를 부르는 건 전부 **Batch job 스레드**다. job 은 어차피 분 단위로 돌고, 한 번에 하나만
 * 돈다. 여기서 스레드를 잡고 기다리는 건 낭비가 아니라 그냥 "순서대로 한다"는 뜻이다.
 */
@Component
class QdrantIndexStore(
	@Value("\${psp.qdrant.url}") baseUrl: String,
) {

	private val http = RestClient.builder().baseUrl(baseUrl).build()

	// ---- 컬렉션 생명주기 ----

	/** `{alias}_{yyyyMMddHHmmss}` 새 버전 컬렉션을 만든다. 필터로 쓸 필드엔 payload 인덱스도 함께 건다. */
	fun createNextVersion(alias: String, dimension: Int): String {
		val name = IndexVersion.newName(alias)
		http.put().uri("/collections/{name}", name)
			.body(
				mapOf(
					"vectors" to mapOf("size" to dimension, "distance" to QdrantContract.DISTANCE),
					// m: 이웃 연결 수, ef_construct: 만들 때 탐색 폭. 크면 정확하지만 색인이 느려진다.
					"hnsw_config" to mapOf(
						"m" to QdrantContract.HNSW_M,
						"ef_construct" to QdrantContract.HNSW_EF_CONSTRUCT,
					),
				),
			)
			.retrieve().toBodilessEntity()

		// payload 인덱스가 없으면 필터가 전수 검사로 떨어진다 — 걸 필드는 미리 걸어둔다.
		QdrantContract.PAYLOAD_INDEXES.forEach { (field, schema) ->
			http.put().uri("/collections/{name}/index?wait=true", name)
				.body(mapOf("field_name" to field, "field_schema" to schema))
				.retrieve().toBodilessEntity()
		}
		return name
	}

	/** alias 가 지금 가리키는 컬렉션들. (없으면 빈 집합) */
	fun collectionsBehind(alias: String): Set<String> =
		aliases().filter { it.alias_name == alias }.map { it.collection_name }.toSet()

	/**
	 * alias 를 newCollection 으로 **원자적으로** 옮긴다.
	 * Qdrant 는 액션 목록을 한 덩어리로 적용하므로 "가리키는 곳이 없는" 순간이 생기지 않는다.
	 * 밀려난 옛 컬렉션들을 반환한다.
	 */
	fun swapAlias(alias: String, newCollection: String): Set<String> {
		val previous = collectionsBehind(alias)
		val actions = previous.map { mapOf("delete_alias" to mapOf("alias_name" to alias)) } +
			listOf(mapOf("create_alias" to mapOf("collection_name" to newCollection, "alias_name" to alias)))
		http.post().uri("/collections/aliases")
			.body(mapOf("actions" to actions))
			.retrieve().toBodilessEntity()
		return previous - newCollection
	}

	fun deleteCollections(names: Set<String>) {
		names.forEach { http.delete().uri("/collections/{name}", it).retrieve().toBodilessEntity() }
	}

	/**
	 * 버전 컬렉션 정리. alias 가 가리키는 **현재 버전(keep 개, 현재 포함)만 남기고** 그보다 낮은
	 * 번호를 지운다. 취소·실패로 남은 고아 컬렉션도 이 규칙으로 함께 사라진다.
	 *
	 * **현재보다 높은 번호는 절대 안 건드린다** — 그건 지금 만들어지는 중인 새 빌드일 수 있다.
	 * 새 버전은 항상 max+1(더 높은 번호)로 생기므로, 이 정리가 색인과 동시에 돌아도 안전하다.
	 * alias 가 없으면(아직 첫 스왑 전) 기준이 없으니 아무것도 지우지 않는다.
	 *
	 * 지운 컬렉션명을 반환한다.
	 */
	fun reconcile(alias: String, keep: Int): Set<String> {
		val current = collectionsBehind(alias).mapNotNull { IndexVersion.tokenOf(alias, it) }.maxOrNull()
			?: return emptySet()   // alias 미설정 → 기준 없음, 정리 보류

		val below = http.get().uri("/collections").retrieve()
			.requiredBody<CollectionsResponse>().result.collections
			.mapNotNull { c -> IndexVersion.tokenOf(alias, c.name)?.let { c.name to it } }
			.filter { it.second < current }   // 문자열 비교 = 시간 비교(고정폭 14자리)
			.sortedByDescending { it.second }

		// 현재보다 낮은 것 중 상위 (keep-1)개는 롤백용으로 남기고, 나머지를 지운다.
		val doomed = below.drop((keep - 1).coerceAtLeast(0)).map { it.first }.toSet()
		deleteCollections(doomed)
		return doomed
	}

	fun count(collection: String): Long =
		http.post().uri("/collections/{name}/points/count", collection)
			.body(mapOf("exact" to true))
			.retrieve().requiredBody<CountResponse>().result.count

	// ---- 점 넣고 빼기 ----

	/** 같은 place_id 면 덮어쓴다 — 재실행에 안전(멱등, ADR 0001). */
	fun upsert(collection: String, points: List<VectorPoint>) {
		if (points.isEmpty()) return
		http.put().uri("/collections/{name}/points?wait=true", collection)
			.body(
				mapOf(
					"points" to points.map {
						mapOf(
							"id" to QdrantContract.pointId(it.placeId),
							"vector" to it.vector.toList(),
							"payload" to it.payload,
						)
					},
				),
			)
			.retrieve().toBodilessEntity()
	}

	/** 없는 점을 지우는 것도 성공으로 친다(멱등). */
	fun delete(collection: String, placeIds: List<String>) {
		if (placeIds.isEmpty()) return
		http.post().uri("/collections/{name}/points/delete?wait=true", collection)
			.body(mapOf("points" to placeIds.map { QdrantContract.pointId(it) }))
			.retrieve().toBodilessEntity()
	}

	// ---- 내부 ----

	/**
	 * alias 조회는 `GET /aliases`, alias 변경은 `POST /collections/aliases` 로 **경로가 다르다.**
	 * (조회를 `/collections/aliases` 로 부르면 404 — 실제로 이 실수로 8분짜리 적재를 날렸다.)
	 */
	private fun aliases(): List<AliasDescription> =
		http.get().uri("/aliases").retrieve().requiredBody<AliasesResponse>().result.aliases
}

// ---- 응답 매핑 (필요한 필드만) ----
// 색인기가 **읽는** 응답만 있다. 검색 결과(ScoredPoint 등)는 질의기 쪽에 있다.

internal data class AliasesResponse(val result: AliasList)
internal data class AliasList(val aliases: List<AliasDescription>)
internal data class AliasDescription(val alias_name: String, val collection_name: String)
internal data class CollectionsResponse(val result: CollectionList)
internal data class CollectionList(val collections: List<CollectionDescription>)
internal data class CollectionDescription(val name: String)
internal data class CountResponse(val result: CountResult)
internal data class CountResult(val count: Long)
