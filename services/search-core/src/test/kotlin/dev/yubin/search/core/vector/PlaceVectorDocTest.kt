package dev.yubin.search.core.vector

import dev.yubin.search.core.embed.PlaceVectorText
import dev.yubin.search.core.place.PlaceRow
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 벡터 쪽 **문서 규칙**을 엔진 없이 못박는다 — 무엇을 임베딩하고, 점에 무엇을 함께 저장하나.
 *
 * 질의 쪽 규칙(필터·거리)은 `search-api` 의 `PlaceVectorsTest` 에 있다. 색인기와 질의기가
 * 갈라진 뒤로 이 둘은 **깨졌을 때 아픈 곳이 다르다**: 여기가 깨지면 색인을 다시 해야 하고,
 * 저기가 깨지면 질의만 고치면 된다 (ADR 0011).
 */
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

	// ---- 무엇을 임베딩하나 ----

	@Test
	fun `임베딩 문장은 이름 카테고리 지역을 담는다`() {
		assertEquals("스타벅스 강남역점. 커피점/카페 카페. 강남구 역삼동", PlaceVectorText.of(row()))
	}

	@Test
	fun `복원한 브랜드는 이름 맨 앞에 들어간다`() {
		// 원천이 '스타벅스 신사역점'을 '신사역'으로만 등록해 둔 경우. 여기 안 넣으면
		// 벡터 채널만 계속 '스타벅스'를 못 찾아, 키워드와 결과가 어긋난다.
		val text = PlaceVectorText.of(row(name = "신사역", branch = null, brand = "스타벅스"))
		assertEquals("스타벅스 신사역. 커피점/카페 카페. 강남구 역삼동", text)
	}

	@Test
	fun `브랜드가 없으면 문장이 달라지지 않는다`() {
		assertEquals(PlaceVectorText.of(row()), PlaceVectorText.of(row(brand = null)))
	}

	@Test
	fun `payload 에도 브랜드가 실린다`() {
		// 벡터만 찾은 결과도 화면에 '[스타벅스] 신사역'으로 보여줄 수 있어야 한다.
		assertEquals("스타벅스", PlaceVectorPayload.of(row(brand = "스타벅스"))["brand"])
		// 상호명이 브랜드가 아니면 필드 자체가 없다. (기본 row 의 이름 '스타벅스' 는
		// 시드 사전에 걸려 브랜드가 잡히므로 여기선 못 쓴다 — 테스트가 실제로 그걸 잡았다.)
		assertTrue("brand" !in PlaceVectorPayload.of(row(name = "먹어도")))
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
		val p = PlaceVectorPayload.of(row())
		assertEquals("P1", p["place_id"])
		assertEquals("강남구", p["sigungu"])
		assertEquals(mapOf("lat" to 37.4979, "lon" to 127.0276), p["location"])
	}

	@Test
	fun `좌표가 없으면 location 키 자체가 없다`() {
		// 키를 null 로 넣으면 Qdrant geo 인덱스가 그 점을 이상하게 취급한다.
		assertTrue("location" !in PlaceVectorPayload.of(row(lat = null, lon = null)))
	}

	// ---- 점 id ----

	@Test
	fun `점 id 는 place_id 로부터 결정적으로 만들어진다`() {
		// 재실행해도 같은 id 여야 upsert/delete 가 멱등하다.
		assertEquals(QdrantStore.pointId("P1"), QdrantStore.pointId("P1"))
		assertTrue(QdrantStore.pointId("P1") != QdrantStore.pointId("P2"))
	}
}
