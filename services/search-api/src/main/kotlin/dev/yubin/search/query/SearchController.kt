package dev.yubin.search.query

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 검색 읽기 경로의 유일한 진입점.
 *
 * 컨트롤러는 **파라미터를 값 객체로 옮기고 서비스를 부르는 것만** 한다. 랭킹·필터 규칙은
 * [PlaceQueries] 에, 실행은 서비스에 있다. 6단계에서 벡터 채널이 붙어도 이 파일은 거의 안 바뀐다.
 */
@RestController
@RequestMapping("/v1")
class SearchController(
	private val searchService: PlaceSearchService,
	private val suggestService: PlaceSuggestService,
) {

	/** 본문 검색: `GET /v1/search?q=역삼 커피&sigungu=강남구&size=10` */
	@GetMapping("/search")
	suspend fun search(
		@RequestParam q: String?,
		@RequestParam(required = false) size: Int?,
		@RequestParam(required = false) page: Int?,
		@RequestParam(required = false) sigungu: String?,
		@RequestParam(required = false) dong: String?,
		@RequestParam(required = false, name = "category") categoryLarge: String?,
		@RequestParam(required = false) lat: Double?,
		@RequestParam(required = false) lon: Double?,
		@RequestParam(required = false, name = "radius") radiusM: Int?,
		@RequestParam(required = false) sort: String?,
	): SearchResponse = searchService.search(
		SearchRequest.of(q, size, page, sigungu, dong, categoryLarge, lat, lon, radiusM, sort),
	)

	/** 자동완성: `GET /v1/suggest?q=스타` */
	@GetMapping("/suggest")
	suspend fun suggest(
		@RequestParam q: String?,
		@RequestParam(required = false) size: Int?,
	): SuggestResponse = suggestService.suggest(SuggestRequest.of(q, size))

	/**
	 * 검색창 한 번의 입력에 필요한 것을 **한 번에** — 추천어 + 결과 미리보기.
	 *
	 * 두 인덱스를 `async` 로 **동시에** 부르고 둘 다 `await` 한다. 순차로 부르면 두 지연의 합이지만
	 * 팬아웃하면 느린 쪽 하나에 수렴한다. 코드는 위→아래로 읽히는데 실행은 논블로킹인 것 —
	 * ADR 0006 에서 WebFlux+코루틴을 고른 이유가 바로 이 모양이다.
	 *
	 * 5단계 벡터 검색이 붙으면 여기에 `async` 가 하나 더 늘어날 뿐, 구조는 그대로다.
	 */
	@GetMapping("/instant")
	suspend fun instant(
		@RequestParam q: String?,
		@RequestParam(required = false, name = "suggestSize") suggestSize: Int?,
		@RequestParam(required = false, name = "previewSize") previewSize: Int?,
	): InstantResponse = coroutineScope {
		val startedAt = System.nanoTime()
		val suggestReq = SuggestRequest.of(q, suggestSize)
		val searchReq = SearchRequest.of(q, size = previewSize ?: PREVIEW_SIZE)

		val suggestions = async { suggestService.suggest(suggestReq) }
		val preview = async { searchService.search(searchReq) }

		// 둘 다 끝난 **뒤에** 시간을 재야 한다. 생성자 인자 안에서 재면 인자 평가 순서 때문에
		// await 이전 값이 찍힌다(실측에서 tookMs=0 으로 드러났던 실수).
		val items = suggestions.await().items
		val hits = preview.await().hits

		InstantResponse(
			query = suggestReq.q,
			tookMs = (System.nanoTime() - startedAt) / 1_000_000,
			suggestions = items,
			preview = hits,
		)
	}

	companion object {
		private const val PREVIEW_SIZE = 5
	}
}
