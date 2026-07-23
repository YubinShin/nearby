package dev.yubin.search.query

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 바깥에서 들어온 값이 안전한 범위로 접히는지 — 검색 API 의 첫 방어선.
 * 여기가 뚫리면 `size=100000` 한 방이 클러스터 부하가 된다.
 */
class SearchRequestTest {

	@Test
	fun `size 는 상한으로 클램핑되고 page 도 범위 안으로 접힌다`() {
		val req = SearchRequest.of(q = "커피", size = 10_000, page = -3)
		assertEquals(SearchRequest.MAX_SIZE, req.size)
		assertEquals(0, req.page)
	}

	@Test
	fun `기본값은 첫 페이지 관련도순`() {
		val req = SearchRequest.of(q = " 커피 ")
		assertEquals("커피", req.q)               // 앞뒤 공백 제거
		assertEquals(SearchRequest.DEFAULT_SIZE, req.size)
		assertEquals(0, req.from)
		assertEquals(SortBy.RELEVANCE, req.sort)
		assertFalse(req.hasGeo)
	}

	@Test
	fun `from 은 page 와 size 의 곱`() {
		assertEquals(60, SearchRequest.of(q = "커피", size = 20, page = 3).from)
	}

	@Test
	fun `좌표가 한쪽만 오면 위치 기능을 아예 끈다`() {
		val req = SearchRequest.of(q = "커피", lat = 37.5, lon = null, radiusM = 500)
		assertFalse(req.hasGeo)
		assertNull(req.lat)
		assertNull(req.radiusM)   // 반경만 남아 조용히 잘못 걸리는 일이 없도록
	}

	@Test
	fun `좌표가 둘 다 오면 반경 기본값이 채워진다`() {
		val req = SearchRequest.of(q = "커피", lat = 37.5, lon = 127.0)
		assertTrue(req.hasGeo)
		assertEquals(SearchRequest.DEFAULT_RADIUS_M, req.radiusM)
	}

	@Test
	fun `반경도 상한으로 클램핑된다`() {
		val req = SearchRequest.of(q = "커피", lat = 37.5, lon = 127.0, radiusM = 9_999_999)
		assertEquals(SearchRequest.MAX_RADIUS_M, req.radiusM)
	}

	@Test
	fun `좌표 없이 거리순을 요청하면 관련도순으로 되돌린다`() {
		// 기준점이 없으면 거리 정렬은 정의되지 않는다 → 에러 대신 조용히 안전한 기본으로.
		assertEquals(SortBy.RELEVANCE, SearchRequest.of(q = "커피", sort = "distance").sort)
		assertEquals(SortBy.DISTANCE, SearchRequest.of(q = "커피", lat = 37.5, lon = 127.0, sort = "DISTANCE").sort)
	}

	@Test
	fun `빈 필터 문자열은 필터 없음으로 취급한다`() {
		val req = SearchRequest.of(q = "커피", sigungu = "   ", dong = "")
		assertNull(req.sigungu)
		assertNull(req.dong)
	}

	@Test
	fun `자동완성도 크기를 클램핑한다`() {
		assertEquals(SuggestRequest.MAX_SIZE, SuggestRequest.of("스타", 500).size)
		assertEquals(SuggestRequest.DEFAULT_SIZE, SuggestRequest.of("스타").size)
		assertEquals("", SuggestRequest.of(null).q)
	}
}
