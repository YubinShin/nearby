package dev.yubin.search.vector

import dev.yubin.search.query.SearchRequest
import dev.yubin.search.query.SearchResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 벡터(뜻) 검색 진입점. 파라미터는 `/v1/search` 와 **일부러 똑같이** 맞췄다 —
 * 같은 질의를 두 채널에 던져 결과를 나란히 비교할 수 있어야 6단계 결합을 설계할 수 있다.
 *
 * 벡터 기능을 끄고 뜨면(`psp.vector.enabled=false`) 이 컨트롤러 자체가 없다.
 * 임베딩 모델이 없는 노드에서 이 엔드포인트만 500 을 뱉는 상황을 만들지 않기 위해서다.
 */
@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(
	name = ["psp.role.query", "psp.vector.enabled"],
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
		@RequestParam(required = false, name = "category") categoryLarge: String?,
		@RequestParam(required = false) lat: Double?,
		@RequestParam(required = false) lon: Double?,
		@RequestParam(required = false, name = "radius") radiusM: Int?,
	): SearchResponse = vectorSearch.search(
		SearchRequest.of(
			q = q, size = size, page = page,
			sigungu = sigungu, dong = dong, categoryLarge = categoryLarge,
			lat = lat, lon = lon, radiusM = radiusM,
			// 벡터 채널은 '거리순' 정렬을 받지 않는다. 뜻으로 뽑은 순서를 거리로 다시 세우면
			// 벡터 점수가 통째로 버려진다 — 거리 다듬기는 7단계에서 결합 뒤에 할 일이다.
			sort = null,
		),
	)
}
