package dev.yubin.search.hybrid

import dev.yubin.search.query.SearchRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(
	name = ["psp.vector.enabled", "psp.hybrid.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class HybridSearchController(private val hybridSearch: HybridSearchService) {
	@GetMapping("/hsearch")
	suspend fun hsearch(
		@RequestParam(required = false) q: String?,
		@RequestParam(required = false) size: Int?,
		@RequestParam(required = false) page: Int?,
		@RequestParam(required = false) sigungu: String?,
		@RequestParam(required = false) dong: String?,
		@RequestParam(required = false, name = "category") categoryLarge: String?,
		@RequestParam(required = false) lat: Double?,
		@RequestParam(required = false) lon: Double?,
		@RequestParam(required = false, name = "radius") radiusM: Int?,
	): HybridResponse = hybridSearch.search(
		SearchRequest.of(
			q = q, size = size, page = page,
			sigungu = sigungu, dong = dong, categoryLarge = categoryLarge,
			lat = lat, lon = lon, radiusM = radiusM,

			sort = null,
		),
	)
}
