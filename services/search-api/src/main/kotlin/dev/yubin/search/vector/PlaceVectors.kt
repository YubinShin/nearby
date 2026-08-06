package dev.yubin.search.vector

import dev.yubin.search.query.SearchRequest
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object PlaceVectors {
	fun filter(req: SearchRequest): Map<String, Any?>? {
		val must = buildList {
			req.sigungu?.let { add(match("sigungu", it)) }
			req.dong?.let { add(match("dong", it)) }
			req.category?.let { add(anyCategoryLevel(it)) }
			if (req.hasGeo && req.radiusM != null) {
				add(
					mapOf(
						"key" to "location",
						"geo_radius" to mapOf(
							"center" to mapOf("lat" to req.lat, "lon" to req.lon),
							"radius" to req.radiusM.toDouble(),
						),
					),
				)
			}
		}
		return if (must.isEmpty()) null else mapOf("must" to must)
	}

	private fun match(key: String, value: String) = mapOf("key" to key, "match" to mapOf("value" to value))

	private fun anyCategoryLevel(value: String) =
		mapOf("should" to CATEGORY_LEVELS.map { match(it, value) })

	val CATEGORY_LEVELS = listOf("category_large", "category_small")

	fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Long {
		val dLat = Math.toRadians(lat2 - lat1)
		val dLon = Math.toRadians(lon2 - lon1)
		val a = sin(dLat / 2) * sin(dLat / 2) +
			cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
		return (2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))).toLong()
	}

	private const val EARTH_RADIUS_M = 6_371_000.0
}
