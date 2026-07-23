package dev.yubin.search.query

/**
 * ES `_source` 를 그대로 받는 모양. **색인된 스키마와 1:1** 이라 필드명이 snake_case 다.
 * 바깥(API)에 그대로 내보내지 않고 [PlaceHit] 로 옮겨 담는다 — 색인 스키마가 바뀌어도
 * API 계약이 따라 흔들리지 않게 하는 경계다.
 */
data class SearchDoc(
	val place_id: String = "",
	val name: String = "",
	val branch: String? = null,
	val brand: String? = null,
	val category_large: String? = null,
	val category_mid: String? = null,
	val category_small: String? = null,
	val sigungu: String? = null,
	val dong: String? = null,
	val road_address: String? = null,
	val jibun_address: String? = null,
	val location: GeoPointValue? = null,
)

data class SuggestDoc(
	val place_id: String = "",
	val name: String = "",
	val brand: String? = null,
	val category_small: String? = null,
	val dong: String? = null,
)

data class GeoPointValue(val lat: Double? = null, val lon: Double? = null)
