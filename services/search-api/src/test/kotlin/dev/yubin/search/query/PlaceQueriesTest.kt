package dev.yubin.search.query

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.json.JsonpUtils
import co.elastic.clients.json.SimpleJsonpMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceQueriesTest {
	private fun json(q: Query): String = JsonpUtils.toJsonString(q, SimpleJsonpMapper.INSTANCE)

	@Test
	fun `the main search is a cross_fields query requiring every term`() {
		val q = json(PlaceQueries.search(SearchRequest.of("역삼 커피")))

		assertTrue("cross_fields" in q, "terms scattered across fields must be tied together, so it has to be cross_fields: $q")
		assertTrue("\"operator\":\"and\"" in q, "the strict query requires every term: $q")
		assertTrue("역삼 커피" in q)
	}

	@Test
	fun `the place name carries the highest weight and the address is secondary`() {
		assertEquals("name^5", PlaceQueries.SEARCH_FIELDS.first())
		assertTrue("road_address" in PlaceQueries.SEARCH_FIELDS)

		assertTrue(PlaceQueries.SEARCH_FIELDS.any { it.startsWith("category_small.txt") })
		assertTrue(PlaceQueries.SEARCH_FIELDS.any { it.startsWith("dong.txt") })
	}

	@Test
	fun `a phrase match on the place name earns a bonus`() {
		val q = json(PlaceQueries.search(SearchRequest.of("스타벅스")))
		assertTrue("match_phrase" in q, "places whose whole name matches must be pushed up: $q")
	}

	@Test
	fun `the fallback query drops the require every term rule`() {
		val strict = json(PlaceQueries.search(SearchRequest.of("역삼 커피 브런치"), relaxed = false))
		val relaxed = json(PlaceQueries.search(SearchRequest.of("역삼 커피 브런치"), relaxed = true))

		assertTrue("\"operator\":\"and\"" in strict)
		assertFalse("\"operator\":\"and\"" in relaxed)

		assertTrue("\"minimum_should_match\":\"70%\"" in relaxed, "the fallback threshold changed: $relaxed")
	}

	@Test
	fun `no filter conditions means no filter clause`() {
		assertTrue(PlaceQueries.filters(SearchRequest.of("커피")).isEmpty())
	}

	@Test
	fun `the administrative region filter uses the unanalyzed keyword field`() {
		val filters = PlaceQueries.filters(SearchRequest.of("커피", sigungu = "강남구", dong = "역삼동"))
		val q = filters.joinToString { json(it) }

		assertEquals(2, filters.size)
		assertTrue("\"sigungu\"" in q && "dong.txt" !in q, "an exact match filter uses the raw keyword field, not .txt: $q")
		assertTrue("강남구" in q && "역삼동" in q)
	}

	@Test
	fun `coordinates attach a radius filter in meters`() {
		val filters = PlaceQueries.filters(
			SearchRequest.of("커피", lat = 37.5006, lon = 127.0366, radiusM = 800),
		)
		val q = json(filters.single())

		assertTrue("geo_distance" in q, q)
		assertTrue("800m" in q, q)
		assertTrue("37.5006" in q && "127.0366" in q, q)
	}

	@Test
	fun `suggest orders by prefix match and name length`() {
		val q = json(PlaceQueries.suggest(SuggestRequest.of("스타")))

		assertTrue("function_score" in q, q)

		assertTrue("\"label\"" in q, "suggest must match on label: $q")
		assertTrue("\"name\"" !in q, "falling back to name makes brand recovery pointless: $q")
		assertTrue("prefix" in q, "a name starting with those characters must be pushed up: $q")
		assertTrue("name_length" in q && "reciprocal" in q, "shorter names first (reciprocal of length): $q")
		assertTrue("multiply" in q, "multiplying the BM25 score preserves the original relevance: $q")
	}

	@Test
	fun `suggest prefix matching is case insensitive`() {
		assertTrue("starbucks" in json(PlaceQueries.suggest(SuggestRequest.of("StarBucks"))))
	}
}
