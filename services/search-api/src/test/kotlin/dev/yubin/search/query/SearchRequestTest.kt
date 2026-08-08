package dev.yubin.search.query

import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebInputException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchRequestTest {
	@Test
	fun `size is clamped to the max and page is folded into range`() {
		val req = SearchRequest.of(q = "커피", size = 10_000, page = -3)
		assertEquals(SearchRequest.MAX_SIZE, req.size)
		assertEquals(0, req.page)
	}

	@Test
	fun `the defaults are the first page ordered by relevance`() {
		val req = SearchRequest.of(q = " 커피 ")
		assertEquals("커피", req.q)
		assertEquals(SearchRequest.DEFAULT_SIZE, req.size)
		assertEquals(0, req.from)
		assertEquals(SortBy.RELEVANCE, req.sort)
		assertFalse(req.hasGeo)
	}

	@Test
	fun `from is page times size`() {
		assertEquals(60, SearchRequest.of(q = "커피", size = 20, page = 3).from)
	}

	@Test
	fun `a coordinate outside the valid range is rejected instead of reaching Elasticsearch`() {
		assertFailsWith<ServerWebInputException> { SearchRequest.of("커피", lat = 91.0, lon = 127.0) }
		assertFailsWith<ServerWebInputException> { SearchRequest.of("커피", lat = -91.0, lon = 127.0) }
		assertFailsWith<ServerWebInputException> { SearchRequest.of("커피", lat = 37.5, lon = 181.0) }
		assertFailsWith<ServerWebInputException> { SearchRequest.of("커피", lat = Double.NaN, lon = 127.0) }
		assertFailsWith<ServerWebInputException> { SearchRequest.of("커피", lat = 37.5, lon = Double.POSITIVE_INFINITY) }
	}

	@Test
	fun `the range boundaries themselves are accepted`() {
		assertEquals(90.0, SearchRequest.of("커피", lat = 90.0, lon = 180.0).lat)
		assertEquals(-180.0, SearchRequest.of("커피", lat = -90.0, lon = -180.0).lon)
	}

	@Test
	fun `only one coordinate turns geo off entirely`() {
		val req = SearchRequest.of(q = "커피", lat = 37.5, lon = null, radiusM = 500)
		assertFalse(req.hasGeo)
		assertNull(req.lat)
		assertNull(req.radiusM)
	}

	@Test
	fun `both coordinates fill in the default radius`() {
		val req = SearchRequest.of(q = "커피", lat = 37.5, lon = 127.0)
		assertTrue(req.hasGeo)
		assertEquals(SearchRequest.DEFAULT_RADIUS_M, req.radiusM)
	}

	@Test
	fun `the radius is clamped to the max as well`() {
		val req = SearchRequest.of(q = "커피", lat = 37.5, lon = 127.0, radiusM = 9_999_999)
		assertEquals(SearchRequest.MAX_RADIUS_M, req.radiusM)
	}

	@Test
	fun `asking for distance sort without coordinates falls back to relevance`() {
		assertEquals(SortBy.RELEVANCE, SearchRequest.of(q = "커피", sort = "distance").sort)
		assertEquals(SortBy.DISTANCE, SearchRequest.of(q = "커피", lat = 37.5, lon = 127.0, sort = "DISTANCE").sort)
	}

	@Test
	fun `a blank filter string is treated as no filter`() {
		val req = SearchRequest.of(q = "커피", sigungu = "   ", dong = "")
		assertNull(req.sigungu)
		assertNull(req.dong)
	}

	@Test
	fun `suggest clamps its size too`() {
		assertEquals(SuggestRequest.MAX_SIZE, SuggestRequest.of("스타", 500).size)
		assertEquals(SuggestRequest.DEFAULT_SIZE, SuggestRequest.of("스타").size)
		assertEquals("", SuggestRequest.of(null).q)
	}
}
