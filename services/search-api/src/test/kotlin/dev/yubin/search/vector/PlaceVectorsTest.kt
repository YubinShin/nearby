package dev.yubin.search.vector

import dev.yubin.search.query.SearchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 벡터 채널의 **질의 쪽** 규칙(필터·거리)을 엔진 없이 못박는다.
 * 색인 쪽 규칙(임베딩 문장·payload)은 `search-core` 의 `PlaceVectorDocTest` 에 있다.
 */
class PlaceVectorsTest {

	// ---- 필터 ----

	@Test
	fun `조건이 없으면 필터를 만들지 않는다`() {
		assertNull(PlaceVectors.filter(SearchRequest.of(q = "카페")))
	}

	@Test
	fun `지역 카테고리 조건은 must 로 묶인다`() {
		val f = PlaceVectors.filter(SearchRequest.of(q = "카페", sigungu = "강남구", categoryLarge = "음식"))!!
		@Suppress("UNCHECKED_CAST")
		val must = f["must"] as List<Map<String, Any?>>
		assertEquals(listOf("sigungu", "category_large"), must.map { it["key"] })
		assertEquals(mapOf("value" to "강남구"), must[0]["match"])
	}

	@Test
	fun `좌표가 있으면 반경 필터가 붙는다`() {
		val f = PlaceVectors.filter(SearchRequest.of(q = "카페", lat = 37.5, lon = 127.0, radiusM = 500))!!
		@Suppress("UNCHECKED_CAST")
		val geo = (f["must"] as List<Map<String, Any?>>).single()["geo_radius"] as Map<*, *>
		assertEquals(500.0, geo["radius"])
	}

	@Test
	fun `좌표가 한쪽만 있으면 반경 필터가 붙지 않는다`() {
		assertNull(PlaceVectors.filter(SearchRequest.of(q = "카페", lat = 37.5)))
	}

	// ---- 거리 ----

	@Test
	fun `하버사인 거리는 실제 거리에 가깝다`() {
		// 강남역 ↔ 역삼역: 실제 약 750m
		val d = PlaceVectors.distanceM(37.4979, 127.0276, 37.5006, 127.0365)
		assertTrue(d in 700..850, "예상 700~850m, 실제 ${d}m")
	}
}
