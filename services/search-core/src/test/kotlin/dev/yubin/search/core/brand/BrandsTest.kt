package dev.yubin.search.core.brand

import dev.yubin.search.core.embed.PlaceVectorText
import dev.yubin.search.core.place.PlaceDocuments
import dev.yubin.search.core.place.PlaceRow
import dev.yubin.search.core.vector.PlaceVectorPayload
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 사람이 관리하는 브랜드 시드(`brands.tsv`)와 브랜드 결정 규칙을 엔진 없이 못박는다. */
class BrandsTest {

	private fun row(name: String, brand: String? = null, branch: String? = null) = PlaceRow(
		placeId = "P1", name = name, branch = branch, brand = brand,
		categoryLarge = null, categoryMid = "커피점/카페", categorySmall = "카페",
		sido = "서울특별시", sigungu = "강남구", dong = "역삼1동",
		jibunAddress = null, roadAddress = null, lon = null, lat = null,
		updatedAt = OffsetDateTime.parse("2026-07-23T00:00:00Z"), deletedAt = null,
	)

	// ---- 이번 결함의 재발 방지선 (크리틱 #21) ----

	@Test
	fun `색인 문서와 임베딩 문장과 payload 가 같은 브랜드를 쓴다`() {
		/*
		 * 전에는 색인 문서만 시드 사전을 보고, 임베딩과 payload 는 복원분만 봤다.
		 * 그래서 `CU` 를 치면 키워드 187건 / 벡터 0건이 나왔다. 세 경로를 한 테스트로 묶어,
		 * 한쪽만 고치는 사고가 다시 나면 여기서 걸리게 한다.
		 */
		val dictionaryOnly = row(name = "씨유역삼점")            // 사전으로만 아는 브랜드
		val recoveredOnly = row(name = "신사역", brand = "스타벅스") // 인허가에서 복원한 브랜드

		for (r in listOf(dictionaryOnly, recoveredOnly)) {
			val expected = Brands.resolve(r.brand, r.name, r.branch)
			assertEquals(expected, PlaceDocuments.searchDoc(r)["brand"], "색인 문서: ${r.name}")
			assertEquals(expected, PlaceVectorPayload.of(r)["brand"], "payload: ${r.name}")
			assertTrue(
				PlaceVectorText.of(r).startsWith("$expected "),
				"임베딩 문장에 브랜드가 없다: ${PlaceVectorText.of(r)}",
			)
		}
	}

	@Test
	fun `브랜드가 없으면 세 경로 모두 아무것도 넣지 않는다`() {
		val r = row(name = "먹어도")
		assertTrue("brand" !in PlaceDocuments.searchDoc(r))
		assertTrue("brand" !in PlaceVectorPayload.of(r))
		assertEquals("먹어도. 커피점/카페 카페. 강남구 역삼1동", PlaceVectorText.of(r))
	}

	@Test
	fun `표시와 임베딩은 규칙이 다르다`() {
		// 표시: 어떤 표기든 이미 있으면 안 붙인다 ('CU CU' 방지).
		// 임베딩: 정규형이 없을 때만 붙인다 — 'CU' 질의와 가까워지려면 그 토큰이 문장에 있어야 한다.
		assertEquals("씨유역삼점", Brands.display("CU", "씨유역삼점"))
		assertEquals("CU 씨유역삼점", Brands.embedText("CU", "씨유역삼점"))

		// 정규형이 이미 있으면 임베딩도 안 붙인다 — '스타벅스 스타벅스' 가 되면 문장이 왜곡된다.
		assertEquals("스타벅스", Brands.embedText("스타벅스", "스타벅스"))
		assertEquals("파리바게뜨 파리바게트논현점", Brands.embedText("파리바게뜨", "파리바게트논현점"))
	}

	// ---- 시드 사전 ----

	@Test
	fun `표기가 갈린 브랜드를 하나로 모은다`() {
		// 이 파일의 존재 이유. 실측: 씨유 176건 / CU 11건으로 갈려 있었다.
		assertEquals("CU", Brands.canonical("씨유역삼점"))
		assertEquals("CU", Brands.canonical("CU청담"))
		assertEquals("GS25", Brands.canonical("지에스25학동"))
		assertEquals("GS25", Brands.canonical("GS25역삼명진점"))
		assertEquals("파리바게뜨", Brands.canonical("파리바게트논현점"))
		assertEquals("서브웨이", Brands.canonical("써브웨이강남점"))
	}

	@Test
	fun `검색용 문자열엔 모든 표기가 들어간다`() {
		val text = Brands.searchText("CU")
		assertTrue("CU" in text && "씨유" in text, text)
	}

	@Test
	fun `긴 표기가 짧은 표기보다 먼저 매칭된다`() {
		assertEquals("매머드커피", Brands.canonical("매머드익스프레스역삼신한점"))
		assertEquals("매머드커피", Brands.canonical("매머드커피강남대로점"))
	}

	@Test
	fun `이름 앞에 와야 브랜드로 본다`() {
		assertNull(Brands.canonical("우리집CU앞분식"))
		assertNull(Brands.canonical("행복한커피빈스토리"), "커피빈이 뒤에 있으면 아니다")
	}

	@Test
	fun `공백과 대소문자는 무시한다`() {
		assertEquals("CU", Brands.canonical("cu 역삼"))
		assertEquals("투썸플레이스", Brands.canonical("투썸 플레이스 강남"))
	}

	@Test
	fun `복원분이 사전보다 우선이다`() {
		// 사전은 이름 규칙일 뿐이고, 복원분은 이 가게 하나를 좌표로 확인한 값이다.
		assertEquals("스타벅스", Brands.resolve("스타벅스", "씨유역삼점"))
		assertEquals("CU", Brands.resolve(null, "씨유역삼점"))
		assertEquals("CU", Brands.resolve("  ", "씨유역삼점"), "빈 문자열은 없는 것으로 본다")
	}

	@Test
	fun `브랜드가 아닌 가게는 null 이다`() {
		assertNull(Brands.canonical("먹어도"))
		assertNull(Brands.canonical("혼밥대왕"))
		assertNull(Brands.canonical(""))
	}

	@Test
	fun `지점명까지 이어 붙여 본다`() {
		assertEquals("CU", Brands.canonical("씨유", "역삼점"))
	}

	@Test
	fun `이름이 이미 브랜드로 시작하면 라벨에 두 번 붙이지 않는다`() {
		assertEquals("CU역삼점", PlaceDocuments.label(row(name = "CU역삼점")))
		assertEquals("씨유역삼점", PlaceDocuments.label(row(name = "씨유역삼점")))
	}

	@Test
	fun `이름에서 빠져 있던 브랜드는 라벨 앞에 세운다`() {
		assertEquals("스타벅스 신사역", PlaceDocuments.label(row(name = "신사역", brand = "스타벅스")))
	}

	@Test
	fun `시드에 중복 표기가 없다`() {
		// 같은 표기가 두 브랜드에 걸리면 어느 쪽이 이길지 파일 순서에 좌우된다.
		val all = Brands.aliases.values.flatten().map { it.replace(" ", "").lowercase() }
		assertEquals(all.size, all.toSet().size, "중복 표기: ${all.groupBy { it }.filter { it.value.size > 1 }.keys}")
	}
}
