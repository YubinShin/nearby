package dev.yubin.search.vector

import dev.yubin.search.embed.PlaceVectorText
import dev.yubin.search.index.PlaceRow
import dev.yubin.search.query.SearchRequest
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 벡터 채널의 규칙(무엇을 임베딩할지 · 필터 · 거리)을 엔진 없이 못박는다. */
class PlaceVectorsTest {

	private fun row(
		placeId: String = "P1",
		name: String = "스타벅스",
		branch: String? = "강남역점",
		categoryMid: String? = "커피점/카페",
		categorySmall: String? = "카페",
		sigungu: String? = "강남구",
		dong: String? = "역삼동",
		lat: Double? = 37.4979,
		lon: Double? = 127.0276,
	) = PlaceRow(
		placeId = placeId, name = name, branch = branch, brand = null,
		categoryLarge = "음식", categoryMid = categoryMid, categorySmall = categorySmall,
		sido = "서울특별시", sigungu = sigungu, dong = dong,
		jibunAddress = "역삼동 123-4", roadAddress = "테헤란로 1",
		lon = lon, lat = lat,
		updatedAt = OffsetDateTime.parse("2026-07-23T00:00:00Z"), deletedAt = null,
	)

	// ---- 무엇을 임베딩하나 ----

	@Test
	fun `임베딩 문장은 이름 카테고리 지역을 담는다`() {
		assertEquals("스타벅스 강남역점. 커피점/카페 카페. 강남구 역삼동", PlaceVectorText.of(row()))
	}

	@Test
	fun `주소는 임베딩하지 않는다`() {
		// 번지·도로명은 의미가 없고 토큰만 먹는다. 위치는 필터가 할 일.
		val text = PlaceVectorText.of(row())
		assertTrue("123-4" !in text && "테헤란로" !in text, text)
	}

	@Test
	fun `빈 값은 조용히 빠지고 구분자만 남지 않는다`() {
		val text = PlaceVectorText.of(row(branch = null, categoryMid = null, categorySmall = null, dong = null))
		assertEquals("스타벅스. 강남구", text)
	}

	// ---- payload ----

	@Test
	fun `payload 에는 필터 필드와 표시 필드가 들어간다`() {
		val p = PlaceVectors.payload(row())
		assertEquals("P1", p["place_id"])
		assertEquals("강남구", p["sigungu"])
		assertEquals(mapOf("lat" to 37.4979, "lon" to 127.0276), p["location"])
	}

	@Test
	fun `좌표가 없으면 location 키 자체가 없다`() {
		// 키를 null 로 넣으면 Qdrant geo 인덱스가 그 점을 이상하게 취급한다.
		assertTrue("location" !in PlaceVectors.payload(row(lat = null, lon = null)))
	}

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

	// ---- 점 id ----

	@Test
	fun `점 id 는 place_id 로부터 결정적으로 만들어진다`() {
		// 재실행해도 같은 id 여야 upsert/delete 가 멱등하다.
		assertEquals(QdrantStore.pointId("P1"), QdrantStore.pointId("P1"))
		assertTrue(QdrantStore.pointId("P1") != QdrantStore.pointId("P2"))
	}
}
