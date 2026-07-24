package dev.yubin.search.hybrid

import dev.yubin.search.query.SearchRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 하이브리드 검색 진입점: `GET /v1/hsearch?q=회 먹을 데`
 *
 * `/v1/search`(키워드)·`/v1/vsearch`(벡터)를 **지우지 않고 남겨 둔다.** 세 엔드포인트에 같은
 * 질의를 던져 나란히 비교할 수 있어야 "합쳐서 나아졌다"를 말이 아니라 숫자로 보일 수 있다.
 * 어느 것을 대표 검색으로 삼을지는 8단계에서 정한다.
 */
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
			// 결합 결과를 거리로 다시 세우면 RRF 순위가 통째로 버려진다 (7단계에서 다룰 일).
			sort = null,
		),
	)
}
