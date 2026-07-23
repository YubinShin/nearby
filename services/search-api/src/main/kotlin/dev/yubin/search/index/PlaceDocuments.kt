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
		// 자동완성 랭킹 신호: 짧은 이름일수록 대표 상호일 확률이 높다 (크리틱 #10).
		put("name_length", r.name.length)
		r.categorySmall?.let { put("category_small", it) }
		r.sigungu?.let { put("sigungu", it) }
		r.dong?.let { put("dong", it) }
		geoPoint(r)?.let { put("location", it) }
		put("updated_at", r.updatedAt.toInstant().toString())
	}

	/** ES geo_point 는 {lat, lon} 형태. 좌표 없으면 필드 자체를 넣지 않는다. */
	private fun geoPoint(r: PlaceRow): Map<String, Double>? =
		if (r.lat != null && r.lon != null) mapOf("lat" to r.lat, "lon" to r.lon) else null
}
