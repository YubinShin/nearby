package dev.yubin.search.core.vector

import dev.yubin.search.core.embed.PlaceVectorText
import dev.yubin.search.core.place.PlaceRow
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceVectorDocTest {
	private fun row(
		placeId: String = "P1",
		name: String = "스타벅스",
		branch: String? = "강남역점",
		brand: String? = null,
		categoryMid: String? = "커피점/카페",
		categorySmall: String? = "카페",
		sigungu: String? = "강남구",
		dong: String? = "역삼동",
		lat: Double? = 37.4979,
		lon: Double? = 127.0276,
	) = PlaceRow(
		placeId = placeId, name = name, branch = branch, brand = brand,
		categoryLarge = "음식", categoryMid = categoryMid, categorySmall = categorySmall,
		sido = "서울특별시", sigungu = sigungu, dong = dong,
		jibunAddress = "역삼동 123-4", roadAddress = "테헤란로 1",
		lon = lon, lat = lat,
		updatedAt = OffsetDateTime.parse("2026-07-23T00:00:00Z"), deletedAt = null,
	)

	@Test
	fun `the embedding text carries name, category and region`() {
		assertEquals("스타벅스 강남역점. 커피점/카페 카페. 강남구 역삼동", PlaceVectorText.of(row()))
	}

	@Test
	fun `the recovered brand goes at the very front of the name`() {
		val text = PlaceVectorText.of(row(name = "신사역", branch = null, brand = "스타벅스"))
		assertEquals("스타벅스 신사역. 커피점/카페 카페. 강남구 역삼동", text)
	}

	@Test
	fun `no brand leaves the text unchanged`() {
		assertEquals(PlaceVectorText.of(row()), PlaceVectorText.of(row(brand = null)))
	}

	@Test
	fun `the payload carries the brand too`() {
		assertEquals("스타벅스", PlaceVectorPayload.of(row(brand = "스타벅스"))["brand"])
		assertTrue("brand" !in PlaceVectorPayload.of(row(name = "먹어도")))
	}

	@Test
	fun `the address is not embedded`() {
		val text = PlaceVectorText.of(row())
		assertTrue("123-4" !in text && "테헤란로" !in text, text)
	}

	@Test
	fun `empty values drop out quietly without leaving bare separators`() {
		val text = PlaceVectorText.of(row(branch = null, categoryMid = null, categorySmall = null, dong = null))
		assertEquals("스타벅스. 강남구", text)
	}

	@Test
	fun `the payload holds filter fields and display fields`() {
		val p = PlaceVectorPayload.of(row())
		assertEquals("P1", p["place_id"])
		assertEquals("강남구", p["sigungu"])
		assertEquals(mapOf("lat" to 37.4979, "lon" to 127.0276), p["location"])
	}

	@Test
	fun `no coordinates means no location key at all`() {
		assertTrue("location" !in PlaceVectorPayload.of(row(lat = null, lon = null)))
	}

	@Test
	fun `the point id is derived deterministically from place_id`() {
		assertEquals(QdrantContract.pointId("P1"), QdrantContract.pointId("P1"))
		assertTrue(QdrantContract.pointId("P1") != QdrantContract.pointId("P2"))
	}
}
