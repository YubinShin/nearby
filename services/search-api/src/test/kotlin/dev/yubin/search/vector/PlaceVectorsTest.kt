package dev.yubin.search.vector

import dev.yubin.search.query.SearchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceVectorsTest {
	@Test
	fun `no conditions builds no filter`() {
		assertNull(PlaceVectors.filter(SearchRequest.of(q = "카페")))
	}

	@Test
	fun `region and category conditions are combined under must`() {
		val f = PlaceVectors.filter(SearchRequest.of(q = "카페", sigungu = "강남구", category = "음식"))!!
		@Suppress("UNCHECKED_CAST")
		val must = f["must"] as List<Map<String, Any?>>
		assertEquals(2, must.size)
		assertEquals("sigungu", must[0]["key"])
		assertEquals(mapOf("value" to "강남구"), must[0]["match"])
	}

	@Test
	fun `a category value matches whichever level carries it`() {
		val f = PlaceVectors.filter(SearchRequest.of(q = "카페", category = "카페"))!!
		@Suppress("UNCHECKED_CAST")
		val should = (f["must"] as List<Map<String, Any?>>).single()["should"] as List<Map<String, Any?>>

		assertEquals(PlaceVectors.CATEGORY_LEVELS, should.map { it["key"] })
		assertTrue(should.all { it["match"] == mapOf("value" to "카페") })
	}

	@Test
	fun `coordinates attach a radius filter`() {
		val f = PlaceVectors.filter(SearchRequest.of(q = "카페", lat = 37.5, lon = 127.0, radiusM = 500))!!
		@Suppress("UNCHECKED_CAST")
		val geo = (f["must"] as List<Map<String, Any?>>).single()["geo_radius"] as Map<*, *>
		assertEquals(500.0, geo["radius"])
	}

	@Test
	fun `only one coordinate attaches no radius filter`() {
		assertNull(PlaceVectors.filter(SearchRequest.of(q = "카페", lat = 37.5)))
	}

	@Test
	fun `the haversine distance is close to the real distance`() {
		val d = PlaceVectors.distanceM(37.4979, 127.0276, 37.5006, 127.0365)
		assertTrue(d in 700..850, "expected 700~850m, actual ${d}m")
	}
}
