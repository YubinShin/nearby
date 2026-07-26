package dev.yubin.search.query

import dev.yubin.search.core.brand.Brands

enum class SortBy { RELEVANCE, DISTANCE }

data class SearchRequest(
	val q: String,
	val size: Int = DEFAULT_SIZE,
	val page: Int = 0,
	val sigungu: String? = null,
	val dong: String? = null,
	val categoryLarge: String? = null,
	val lat: Double? = null,
	val lon: Double? = null,
	val radiusM: Int? = null,
	val sort: SortBy = SortBy.RELEVANCE,
) {
	val from: Int get() = page * size

	val hasGeo: Boolean get() = lat != null && lon != null

	companion object {
		const val DEFAULT_SIZE = 10
		const val MAX_SIZE = 50
		const val MAX_PAGE = 99
		const val DEFAULT_RADIUS_M = 2_000
		const val MAX_RADIUS_M = 50_000

		fun of(
			q: String?,
			size: Int? = null,
			page: Int? = null,
			sigungu: String? = null,
			dong: String? = null,
			categoryLarge: String? = null,
			lat: Double? = null,
			lon: Double? = null,
			radiusM: Int? = null,
			sort: String? = null,
		): SearchRequest {
			val geo = lat != null && lon != null
			return SearchRequest(
				q = q?.trim().orEmpty(),
				size = (size ?: DEFAULT_SIZE).coerceIn(1, MAX_SIZE),
				page = (page ?: 0).coerceIn(0, MAX_PAGE),
				sigungu = sigungu?.trim()?.ifBlank { null },
				dong = dong?.trim()?.ifBlank { null },
				categoryLarge = categoryLarge?.trim()?.ifBlank { null },
				lat = lat.takeIf { geo },
				lon = lon.takeIf { geo },
				radiusM = if (geo) (radiusM ?: DEFAULT_RADIUS_M).coerceIn(1, MAX_RADIUS_M) else null,

				sort = if (geo && sort.equals("distance", ignoreCase = true)) SortBy.DISTANCE else SortBy.RELEVANCE,
			)
		}
	}
}

data class SuggestRequest(val q: String, val size: Int = DEFAULT_SIZE) {
	companion object {
		const val DEFAULT_SIZE = 8
		const val MAX_SIZE = 20

		fun of(q: String?, size: Int? = null) =
			SuggestRequest(q?.trim().orEmpty(), (size ?: DEFAULT_SIZE).coerceIn(1, MAX_SIZE))
	}
}

data class PlaceHit(
	val placeId: String,
	val name: String,
	val branch: String?,

	val brand: String? = null,
	val category: String?,
	val address: String?,
	val sigungu: String?,
	val dong: String?,
	val lat: Double?,
	val lon: Double?,
	val score: Double,

	val distanceM: Long? = null,

	val highlight: List<String> = emptyList(),
) {
	val label: String get() = Brands.display(brand, name)
}

data class SearchResponse(
	val query: String,
	val total: Long,
	val page: Int,
	val size: Int,
	val tookMs: Long,

	val relaxed: Boolean = false,
	val hits: List<PlaceHit> = emptyList(),
)

data class SuggestItem(
	val placeId: String,
	val name: String,

	val brand: String? = null,
	val category: String?,
	val dong: String?,
	val score: Double,
) {
	val label: String get() = Brands.display(brand, name)
}

data class SuggestResponse(
	val query: String,
	val tookMs: Long,
	val items: List<SuggestItem> = emptyList(),
)

data class InstantResponse(
	val query: String,
	val tookMs: Long,
	val suggestions: List<SuggestItem> = emptyList(),
	val preview: List<PlaceHit> = emptyList(),
)
