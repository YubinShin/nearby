package dev.yubin.search.core.place

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
