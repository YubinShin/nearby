package dev.yubin.search.embed

import dev.yubin.search.brand.Brands
import dev.yubin.search.index.PlaceRow

/**
 * **무엇을 벡터로 만들 것인가.** 벡터 검색 품질의 절반은 여기서 갈린다 (ADR 0010).
 *
 * 키워드 검색은 필드별로 가중치를 줄 수 있지만(ADR 0009), 벡터는 문장 하나를 통째로 넣는다.
 * 그래서 "어떤 문장으로 빚을지"가 곧 랭킹 설계다.
 *
 * 넣은 것과 이유:
 * - **브랜드·상호명·지점명** — 검색의 주 대상. 브랜드는 원천에서 빠져 있던 걸 복원한 값이라
 *   (`place_brand`) 여기 안 넣으면 벡터 채널만 `스타벅스` 를 계속 못 찾는다. 실제로 키워드 쪽만
 *   먼저 고쳤더니 하이브리드 결과에 `스타커피`·`스타카페` 같은 게 섞였다.
 * - **카테고리(중·소분류)** — 뜻으로 찾기의 핵심 재료. "회 먹을 데" 같은 질의는 상호명이 아니라
 *   `한식/횟집` 같은 카테고리와 의미가 통한다. 카테고리가 없으면 벡터 검색을 할 이유가 절반 준다.
 * - **시군구·행정동** — "역삼동 조용한 카페"처럼 지역어가 섞인 질의를 받아내려고.
 *
 * 뺀 것과 이유:
 * - **번지·도로명 주소** — 숫자와 도로명은 의미가 거의 없는데 토큰만 잡아먹는다. 위치로 좁히는 건
 *   벡터가 아니라 **필터**(반경·행정동)가 할 일이다 — 정확히 되는 걸 굳이 근사로 하지 않는다.
 * - **대분류** — 중·소분류에 이미 포함된 정보라 중복이다.
 */
object PlaceVectorText {

	/**
	 * 장소 한 건을 임베딩할 한 문장으로 빚는다.
	 * 예: `"스타벅스 강남역점. 커피점/카페 카페. 강남구 역삼동"`
	 */
	fun of(r: PlaceRow): String {
		// 브랜드는 Brands 한 곳에서 정한다 — 복원분과 시드 사전을 여기서 따로 다루면
		// 벡터 채널만 사전 브랜드를 모르게 된다(크리틱 #21, 실제로 그랬다).
		val brand = Brands.resolve(r.brand, r.name, r.branch)
		val name = listOfNotNull(Brands.embedText(brand, r.name), r.branch).joinToString(" ")
		val category = listOfNotNull(r.categoryMid, r.categorySmall).distinct().joinToString(" ")
		val region = listOfNotNull(r.sigungu, r.dong).joinToString(" ")
		return listOf(name, category, region).filter { it.isNotBlank() }.joinToString(". ")
	}
}
