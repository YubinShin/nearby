package dev.yubin.search.index

/**
 * 원천 행(PlaceRow)을 **용도별 ES 문서**로 바꾼다 (ADR 0002 — 용도별 인덱스 분리).
 * - 본문(search): 관련도 검색에 필요한 필드 전부
 * - 자동완성(suggest): 이름 위주 가벼운 문서
 */
object PlaceDocuments {

	fun searchDoc(r: PlaceRow): Map<String, Any?> = buildMap {
		put("place_id", r.placeId)
		put("name", r.name)
		r.branch?.let { put("branch", it) }
		brandOf(r)?.let {
			// 표시·필터용 정규형 하나.
			put("brand", it)
			// 매칭용 — 모든 표기를 함께 넣는다. 'CU' 로도 '씨유' 로도 걸려야 한다.
			put("brand_text", BrandDictionary.searchText(it))
		}
		r.categoryLarge?.let { put("category_large", it) }
		r.categoryMid?.let { put("category_mid", it) }
		r.categorySmall?.let { put("category_small", it) }
		r.sido?.let { put("sido", it) }
		r.sigungu?.let { put("sigungu", it) }
		r.dong?.let { put("dong", it) }
		r.jibunAddress?.let { put("jibun_address", it) }
		r.roadAddress?.let { put("road_address", it) }
		geoPoint(r)?.let { put("location", it) }
		put("updated_at", r.updatedAt.toInstant().toString())
	}

	fun suggestDoc(r: PlaceRow): Map<String, Any?> = buildMap {
		put("place_id", r.placeId)
		put("name", r.name)
		brandOf(r)?.let { put("brand", it) }
		/*
		 * 자동완성이 **실제로 매칭하고 보여주는** 값. 본문 검색과 달리 여기서는 브랜드를 별도
		 * 필드로 두는 것만으로 부족하다 — '스타'를 쳤을 때 드롭다운에 뜨는 글자가 `개포동`이면
		 * 사용자에게 아무 의미가 없다. 브랜드를 앞에 붙여 **한 덩어리로** 색인한다.
		 * (자동완성 인덱스는 원래 표시 목적의 파생 인덱스다 — ADR 0002)
		 */
		put("label", label(r))
		// 자동완성 랭킹 신호: 짧은 이름일수록 대표 상호일 확률이 높다 (크리틱 #10).
		put("name_length", r.name.length)
		r.categorySmall?.let { put("category_small", it) }
		r.sigungu?.let { put("sigungu", it) }
		r.dong?.let { put("dong", it) }
		geoPoint(r)?.let { put("location", it) }
		put("updated_at", r.updatedAt.toInstant().toString())
	}

	/**
	 * 이 장소의 브랜드. **두 원천을 합친다.**
	 *  1. 인허가와 좌표를 맞춰 **복원한** 값 (`place_brand`) — 원천에 이름이 아예 없던 스타벅스
	 *  2. 사람이 관리하는 **시드 사전** (`brands.tsv`) — 표기가 갈린 CU/씨유 같은 것
	 * 복원값이 우선이다. 그쪽은 이 가게 하나를 보고 판단한 것이고, 사전은 이름 규칙일 뿐이다.
	 */
	private fun brandOf(r: PlaceRow): String? =
		r.brand ?: BrandDictionary.canonical(r.name, r.branch)

	/**
	 * 자동완성이 매칭하고 보여줄 글자.
	 *
	 * 이름이 **이미 브랜드로 시작하면 붙이지 않는다.** 상호가 `CU` 인 편의점에 브랜드 `CU` 를
	 * 앞에 또 붙이면 드롭다운에 `CU CU` 가 뜬다(실측). 브랜드가 이름에서 빠져 있던 경우
	 * (`신사역` → `스타벅스 신사역`)에만 앞에 세우는 게 이 필드의 목적이다.
	 */
	internal fun label(r: PlaceRow): String = BrandDictionary.display(brandOf(r), r.name)

	/** ES geo_point 는 {lat, lon} 형태. 좌표 없으면 필드 자체를 넣지 않는다. */
	private fun geoPoint(r: PlaceRow): Map<String, Double>? =
		if (r.lat != null && r.lon != null) mapOf("lat" to r.lat, "lon" to r.lon) else null
}
