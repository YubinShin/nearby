package dev.yubin.search.core.place

import dev.yubin.search.core.brand.Brands

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
		Brands.resolve(r.brand, r.name, r.branch)?.let {
			// 표시·필터용 정규형 하나.
			put("brand", it)
			// 매칭용 — 모든 표기를 함께 넣는다. 'CU' 로도 '씨유' 로도 걸려야 한다.
			put("brand_text", Brands.searchText(it))
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
		Brands.resolve(r.brand, r.name, r.branch)?.let { put("brand", it) }
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

	/** 자동완성이 매칭하고 보여줄 글자. 규칙은 [Brands.display] 에 있다. */
	internal fun label(r: PlaceRow): String =
		Brands.display(Brands.resolve(r.brand, r.name, r.branch), r.name)

	/** ES geo_point 는 {lat, lon} 형태. 좌표 없으면 필드 자체를 넣지 않는다. */
	private fun geoPoint(r: PlaceRow): Map<String, Double>? =
		if (r.lat != null && r.lon != null) mapOf("lat" to r.lat, "lon" to r.lon) else null
}
