package dev.yubin.search.core.place

import dev.yubin.search.core.brand.Brands

object PlaceDocuments {
	fun searchDoc(r: PlaceRow): Map<String, Any?> = buildMap {
		put("place_id", r.placeId)
		put("name", r.name)
		r.branch?.let { put("branch", it) }
		Brands.resolve(r.brand, r.name, r.branch)?.let {
			put("brand", it)

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

		put("label", label(r))

		put("name_length", r.name.length)
		r.categorySmall?.let { put("category_small", it) }
		r.sigungu?.let { put("sigungu", it) }
		r.dong?.let { put("dong", it) }
		geoPoint(r)?.let { put("location", it) }
		put("updated_at", r.updatedAt.toInstant().toString())
	}

	internal fun label(r: PlaceRow): String =
		Brands.display(Brands.resolve(r.brand, r.name, r.branch), r.name)

	private fun geoPoint(r: PlaceRow): Map<String, Double>? =
		if (r.lat != null && r.lon != null) mapOf("lat" to r.lat, "lon" to r.lon) else null
}
