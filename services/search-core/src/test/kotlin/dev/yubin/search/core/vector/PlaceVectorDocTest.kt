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
	fun `임베딩 문장은 이름 카테고리 지역을 담는다`() {
		assertEquals("스타벅스 강남역점. 커피점/카페 카페. 강남구 역삼동", PlaceVectorText.of(row()))
	}

	@Test
	fun `복원한 브랜드는 이름 맨 앞에 들어간다`() {
		val text = PlaceVectorText.of(row(name = "신사역", branch = null, brand = "스타벅스"))
		assertEquals("스타벅스 신사역. 커피점/카페 카페. 강남구 역삼동", text)
	}

	@Test
	fun `브랜드가 없으면 문장이 달라지지 않는다`() {
		assertEquals(PlaceVectorText.of(row()), PlaceVectorText.of(row(brand = null)))
	}

	@Test
	fun `payload 에도 브랜드가 실린다`() {
		assertEquals("스타벅스", PlaceVectorPayload.of(row(brand = "스타벅스"))["brand"])

		assertTrue("brand" !in PlaceVectorPayload.of(row(name = "먹어도")))
	}

	@Test
	fun `주소는 임베딩하지 않는다`() {
		val text = PlaceVectorText.of(row())
		assertTrue("123-4" !in text && "테헤란로" !in text, text)
	}

	@Test
	fun `빈 값은 조용히 빠지고 구분자만 남지 않는다`() {
		val text = PlaceVectorText.of(row(branch = null, categoryMid = null, categorySmall = null, dong = null))
		assertEquals("스타벅스. 강남구", text)
	}

	@Test
	fun `payload 에는 필터 필드와 표시 필드가 들어간다`() {
		val p = PlaceVectorPayload.of(row())
		assertEquals("P1", p["place_id"])
		assertEquals("강남구", p["sigungu"])
		assertEquals(mapOf("lat" to 37.4979, "lon" to 127.0276), p["location"])
	}

	@Test
	fun `좌표가 없으면 location 키 자체가 없다`() {
		assertTrue("location" !in PlaceVectorPayload.of(row(lat = null, lon = null)))
	}

	@Test
	fun `점 id 는 place_id 로부터 결정적으로 만들어진다`() {
		assertEquals(QdrantContract.pointId("P1"), QdrantContract.pointId("P1"))
		assertTrue(QdrantContract.pointId("P1") != QdrantContract.pointId("P2"))
	}
}
