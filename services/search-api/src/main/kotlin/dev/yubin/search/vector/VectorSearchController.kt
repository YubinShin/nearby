package dev.yubin.search.vector

import dev.yubin.search.debug.capturing
import dev.yubin.search.query.SearchRequest
import dev.yubin.search.query.SearchResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(
	name = ["psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class VectorSearchController(private val vectorSearch: PlaceVectorSearchService) {
	@GetMapping("/vsearch")
	suspend fun vsearch(
		@RequestParam(required = false) q: String?,
		@RequestParam(required = false) size: Int?,
		@RequestParam(required = false) page: Int?,
		@RequestParam(required = false) sigungu: String?,
		@RequestParam(required = false) dong: String?,
		@RequestParam(required = false) category: String?,
		@RequestParam(required = false) lat: Double?,
		@RequestParam(required = false) lon: Double?,
		@RequestParam(required = false, name = "radius") radiusM: Int?,
		@RequestParam(required = false) debug: Boolean?,
	): SearchResponse = capturing(debug) {
		vectorSearch.search(
			SearchRequest.of(
				q = q,
				size = size,
				page = page,
				sigungu = sigungu,
				dong = dong,
				category = category,
				lat = lat,
				lon = lon,
				radiusM = radiusM,
				sort = null,
			),
		)
	}
}
