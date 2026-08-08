package dev.yubin.search.ask

import org.junit.jupiter.api.Test
import org.springframework.web.server.ServerWebInputException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskQueryPlannerTest {
	@Test
	fun `the geo anchor and the category hint are folded into the query text`() {
		val plan = plan(
			raw = "역삼동 조용히 공부할 곳",
			parsed = ParsedQuery(keyword = "공부", categoryHint = "스터디카페", geoAnchor = "역삼동"),
		)
		assertEquals("역삼동 공부 스터디카페", plan.q)
	}

	@Test
	fun `folding is recorded as unmapped because neither becomes an exact filter`() {
		val plan = plan(
			raw = "역삼동 조용히 공부할 곳",
			parsed = ParsedQuery(keyword = "공부", categoryHint = "스터디카페", geoAnchor = "역삼동"),
		)
		assertEquals(listOf("geo_anchor", "category_hint"), plan.unmapped)
	}

	@Test
	fun `a repeated token is not sent twice`() {
		val plan = plan(raw = "카페", parsed = ParsedQuery(keyword = "카페", categoryHint = "카페"))
		assertEquals("카페", plan.q)
	}

	@Test
	fun `the radius is dropped when the caller sent no coordinates`() {
		val plan = plan(raw = "강남역 500m 안에 편의점", parsed = ParsedQuery(keyword = "편의점", geoAnchor = "강남역", radiusM = 500))
		assertNull(plan.radius)
		assertTrue("radius_m" in plan.unmapped)
	}

	@Test
	fun `the radius rides along once the caller sends coordinates`() {
		val plan = plan(
			raw = "강남역 500m 안에 편의점",
			parsed = ParsedQuery(keyword = "편의점", geoAnchor = "강남역", radiusM = 500),
			lat = 37.4979,
			lon = 127.0276,
		)
		assertEquals(500, plan.radius)
		assertEquals(37.4979, plan.lat)
		assertTrue("radius_m" !in plan.unmapped)
	}

	@Test
	fun `coordinates without a parsed radius report the radius the platform applies`() {
		val plan = plan(
			raw = "미용실",
			parsed = ParsedQuery(keyword = "미용실"),
			lat = 37.4979,
			lon = 127.0276,
		)
		assertEquals(AskQueryPlanner.DEFAULT_RADIUS_M, plan.radius)
	}

	@Test
	fun `a failed parse with coordinates reports the platform radius too`() {
		val plan = plan(raw = "미용실", parsed = null, lat = 37.4979, lon = 127.0276)
		assertEquals(AskQueryPlanner.DEFAULT_RADIUS_M, plan.radius)
	}

	@Test
	fun `the radius is clamped like the platform clamps it`() {
		val plan = plan(
			raw = "편의점",
			parsed = ParsedQuery(keyword = "편의점", radiusM = 9_999_999),
			lat = 37.4979,
			lon = 127.0276,
		)
		assertEquals(AskQueryPlanner.MAX_RADIUS_M, plan.radius)
	}

	@Test
	fun `a coordinate outside the valid range is rejected instead of blaming search-api`() {
		assertFailsWith<ServerWebInputException> {
			plan(raw = "카페", parsed = ParsedQuery(keyword = "카페"), lat = 999.0, lon = 127.0)
		}
		assertFailsWith<ServerWebInputException> {
			plan(raw = "카페", parsed = null, lat = 37.5, lon = 181.0)
		}
		assertFailsWith<ServerWebInputException> {
			plan(raw = "카페", parsed = null, lat = Double.NaN, lon = 127.0)
		}
	}

	@Test
	fun `only one coordinate turns geo off entirely`() {
		val plan = plan(raw = "편의점", parsed = ParsedQuery(keyword = "편의점", radiusM = 500), lat = 37.4979, lon = null)
		assertNull(plan.lat)
		assertNull(plan.radius)
		assertTrue("radius_m" in plan.unmapped)
	}

	@Test
	fun `a failed parse sends the raw query through untouched`() {
		val plan = plan(raw = " 회 먹을 데 ", parsed = null)
		assertEquals("회 먹을 데", plan.q)
		assertEquals(emptyList(), plan.unmapped)
	}

	@Test
	fun `an empty keyword falls back to the raw query`() {
		val plan = plan(raw = "회 먹을 데", parsed = ParsedQuery(keyword = "   "))
		assertEquals("회 먹을 데", plan.q)
	}

	@Test
	fun `size defaults to the configured value and is clamped to the platform max`() {
		assertEquals(20, plan(raw = "카페", parsed = ParsedQuery(keyword = "카페")).size)
		assertEquals(AskQueryPlanner.MAX_SIZE, plan(raw = "카페", parsed = ParsedQuery(keyword = "카페"), size = 10_000).size)
		assertEquals(1, plan(raw = "카페", parsed = ParsedQuery(keyword = "카페"), size = 0).size)
	}

	private fun plan(
		raw: String,
		parsed: ParsedQuery?,
		size: Int? = null,
		lat: Double? = null,
		lon: Double? = null,
	) = AskQueryPlanner.plan(query = raw, parsed = parsed, defaultSize = 20, size = size, lat = lat, lon = lon)
}
