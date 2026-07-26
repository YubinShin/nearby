package dev.yubin.search.core.vector

import dev.yubin.search.core.brand.Brands
import dev.yubin.search.core.place.PlaceRow

object PlaceVectorPayload {
	fun of(r: PlaceRow): Map<String, Any?> = buildMap {
		put("place_id", r.placeId)
		put("name", r.name)
		r.branch?.let { put("branch", it) }

		Brands.resolve(r.brand, r.name, r.branch)?.let { put("brand", it) }
		r.categoryLarge?.let { put("category_large", it) }
		r.categorySmall?.let { put("category_small", it) }
		r.sigungu?.let { put("sigungu", it) }
		r.dong?.let { put("dong", it) }
		if (r.lat != null && r.lon != null) put("location", mapOf("lat" to r.lat, "lon" to r.lon))
	}
}
