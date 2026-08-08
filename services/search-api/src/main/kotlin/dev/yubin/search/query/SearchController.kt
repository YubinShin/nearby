package dev.yubin.search.query

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class SearchController(
	private val searchService: PlaceSearchService,
	private val suggestService: PlaceSuggestService,
) {
	@GetMapping("/search")
	suspend fun search(
		@RequestParam q: String?,
		@RequestParam(required = false) size: Int?,
		@RequestParam(required = false) page: Int?,
		@RequestParam(required = false) sigungu: String?,
		@RequestParam(required = false) dong: String?,
		@RequestParam(required = false) category: String?,
		@RequestParam(required = false) lat: Double?,
		@RequestParam(required = false) lon: Double?,
		@RequestParam(required = false, name = "radius") radiusM: Int?,
		@RequestParam(required = false) sort: String?,
	): SearchResponse = searchService.search(
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
			sort = sort,
		),
	)

	@GetMapping("/suggest")
	suspend fun suggest(
		@RequestParam q: String?,
		@RequestParam(required = false) size: Int?,
	): SuggestResponse = suggestService.suggest(SuggestRequest.of(q, size))

	@GetMapping("/instant")
	suspend fun instant(
		@RequestParam q: String?,
		@RequestParam(required = false) suggestSize: Int?,
		@RequestParam(required = false) previewSize: Int?,
	): InstantResponse = coroutineScope {
		val startedAt = System.nanoTime()
		val suggestReq = SuggestRequest.of(q, suggestSize)
		val searchReq = SearchRequest.of(q, size = previewSize ?: PREVIEW_SIZE)

		val suggestions = async { suggestService.suggest(suggestReq) }
		val preview = async { searchService.search(searchReq) }

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
