package dev.yubin.search.core.vector

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
 * 색인기와 질의기가 **반드시 같아야 하는** Qdrant 규칙들 (ADR 0007 · 0013).
 *
 * ### 왜 HTTP 클라이언트는 여기 없나
 * 전에는 `QdrantStore` 라는 클래스 하나가 core 에 있었고 두 앱이 그걸 같이 썼다. 그런데 그
 * 클래스는 `WebClient`(리액티브)를 쓰고 있었고, **그것 때문에 색인기 클래스패스에 WebFlux 가
 * 딸려 왔다.** 색인기는 동시성이 1인 배치라 리액티브가 값을 하는 축이 아예 없는데도 그 런타임을
 * 짊어지고 있었던 것이다 (ADR 0013).
 *
 * 그래서 **호출 방법은 각 앱이 소유하고, 규칙만 여기 남긴다.**
 * - `search-api` → `QdrantSearchStore` (WebClient, suspend — 동시 질의가 많다)
 * - `indexer-batch` → `QdrantIndexStore` (RestClient, 블로킹 — 한 번에 job 하나다)
 *
 * 두 앱이 쓰는 **메서드가 거의 겹치지 않아서** 이 분리로 생기는 중복이 거의 없다. 질의기는
 * `query` 하나만, 색인기는 컬렉션 생명주기와 upsert/delete 만 쓴다.
 *
 * ### 그럼 여기 남는 건 무엇인가
 * "**어긋나면 오류 없이 결과만 조용히 나빠지는 것**" 뿐이다 — core 의 존재 이유 그대로 (ADR 0011).
 * 예를 들어 색인기가 `dong` 에 payload 인덱스를 안 걸면 질의기의 `dong` 필터는 **오류 없이**
 * 전수 검사로 떨어진다. 느려지기만 하고 아무도 모른다.
 */
object QdrantContract {

	/**
	 * Qdrant 의 점 id 는 **부호 없는 정수이거나 UUID** 여야 한다. 우리 place_id 는 문자열이라
	 * 그대로 못 쓴다. 그래서 place_id 로부터 **결정적으로** UUID 를 만든다 —
	 * 같은 place_id 는 언제 어디서 계산해도 같은 id 가 되어야 upsert/delete 가 멱등해진다.
	 * (원본 place_id 는 payload 에 그대로 담아 되돌릴 수 있게 한다.)
	 *
	 * 색인기가 여럿이 되어도(`indexer-stream`) 같은 규칙을 써야 하므로 core 가 소유한다.
	 */
	fun pointId(placeId: String): String =
		UUID.nameUUIDFromBytes(placeId.toByteArray(Charsets.UTF_8)).toString()

	/**
	 * 거리 함수. **색인 시점에 정해지고 질의는 거기 따른다** — 컬렉션을 만들 때 박히는 값이라
	 * 질의기가 다른 걸 기대해도 바꿀 수 없다. 임베딩 모델(E5)이 정규화된 벡터를 내므로 Cosine.
	 */
	const val DISTANCE = "Cosine"

	/** 이웃 연결 수. 크면 정확하지만 색인이 느려지고 메모리를 더 쓴다. */
	const val HNSW_M = 16

	/** 만들 때 탐색 폭. 색인 시점 손잡이. */
	const val HNSW_EF_CONSTRUCT = 100

	/**
	 * 질의 시 탐색 폭. 키우면 정확(recall)해지고 느려진다 — **질의 시점에 조절 가능한** 손잡이.
	 * 색인 파라미터와 짝이라 같은 자리에 둔다.
	 */
	const val HNSW_EF_SEARCH = 128

	/**
	 * 필터로 쓸 payload 필드 → Qdrant 스키마.
	 *
	 * **색인기가 걸고 질의기가 쓴다.** 여기 없는 필드로 필터하면 Qdrant 는 오류를 내지 않고
	 * 전수 검사로 떨어진다 — 조용히 느려지는 종류의 어긋남이라 core 가 소유해야 한다.
	 */
	val PAYLOAD_INDEXES = mapOf(
		"sigungu" to "keyword",
		"dong" to "keyword",
		"category_large" to "keyword",
		"location" to "geo",
	)
}
