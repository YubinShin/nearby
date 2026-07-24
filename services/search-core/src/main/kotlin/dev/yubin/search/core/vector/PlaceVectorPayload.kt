package dev.yubin.search.core.vector

import dev.yubin.search.core.brand.Brands
import dev.yubin.search.core.place.PlaceRow

/**
 * **Qdrant 점에 함께 저장할 부속 정보** — 벡터 쪽 문서 스키마다.
 *
 * ES 문서([dev.yubin.search.core.place.PlaceDocuments])와 같은 이유로 core 에 있다.
 * 여기서 정한 키(`sigungu`·`location`…)를 색인기는 **쓰고** 질의기는 **읽는다.**
 * 두 앱이 따로 배포되는데 이 키가 한쪽에서만 바뀌면 예외 하나 없이 필터만 조용히 빈다.
 *
 * ES 문서와 내용이 겹치는데도 복제하는 이유:
 * 1. **필터**(시군구·행정동·반경)는 payload 인덱스가 있어야 벡터 탐색 중에 같이 걸린다.
 *    payload 가 없으면 "일단 100개 뽑고 앱에서 거른다"가 되어 필터가 셀수록 결과가 빈다.
 * 2. 벡터 채널만으로도 응답을 만들 수 있어야 한다 — 결과 한 건마다 ES 를 다시 부르면
 *    6단계 하이브리드에서 왕복이 두 배가 된다.
 * 대신 **표시에 필요한 최소한만** 담는다. 주소 전문 같은 건 넣지 않는다.
 */
object PlaceVectorPayload {

	fun of(r: PlaceRow): Map<String, Any?> = buildMap {
		put("place_id", r.placeId)
		put("name", r.name)
		r.branch?.let { put("branch", it) }
		// 브랜드. 벡터만 찾은 결과도 `[스타벅스] 개포동` 으로 보여줄 수 있어야 한다.
		// 색인 문서·임베딩 문장과 **같은 함수**로 정한다 (크리틱 #21).
		Brands.resolve(r.brand, r.name, r.branch)?.let { put("brand", it) }
		r.categoryLarge?.let { put("category_large", it) }
		r.categorySmall?.let { put("category_small", it) }
		r.sigungu?.let { put("sigungu", it) }
		r.dong?.let { put("dong", it) }
		if (r.lat != null && r.lon != null) put("location", mapOf("lat" to r.lat, "lon" to r.lon))
	}
}
