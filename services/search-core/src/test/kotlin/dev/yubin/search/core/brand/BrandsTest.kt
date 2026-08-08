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

class BrandsTest {
	private fun row(name: String, brand: String? = null, branch: String? = null) = PlaceRow(
		placeId = "P1", name = name, branch = branch, brand = brand,
		categoryLarge = null, categoryMid = "커피점/카페", categorySmall = "카페",
		sido = "서울특별시", sigungu = "강남구", dong = "역삼1동",
		jibunAddress = null, roadAddress = null, lon = null, lat = null,
		updatedAt = OffsetDateTime.parse("2026-07-23T00:00:00Z"), deletedAt = null,
	)

	@Test
	fun `the search doc, the embedding text and the payload use the same brand`() {
		val dictionaryOnly = row(name = "씨유역삼점")
		val recoveredOnly = row(name = "신사역", brand = "스타벅스")

		for (r in listOf(dictionaryOnly, recoveredOnly)) {
			val expected = Brands.resolve(r.brand, r.name, r.branch)
			assertEquals(expected, PlaceDocuments.searchDoc(r)["brand"], "search doc: ${r.name}")
			assertEquals(expected, PlaceVectorPayload.of(r)["brand"], "payload: ${r.name}")
			assertTrue(
				PlaceVectorText.of(r).startsWith("$expected "),
				"the embedding text has no brand: ${PlaceVectorText.of(r)}",
			)
		}
	}

	@Test
	fun `no brand means none of the three paths add anything`() {
		val r = row(name = "먹어도")
		assertTrue("brand" !in PlaceDocuments.searchDoc(r))
		assertTrue("brand" !in PlaceVectorPayload.of(r))
		assertEquals("먹어도. 커피점/카페 카페. 강남구 역삼1동", PlaceVectorText.of(r))
	}

	@Test
	fun `display and embedding follow different rules`() {
		assertEquals("씨유역삼점", Brands.display("CU", "씨유역삼점"))
		assertEquals("CU 씨유역삼점", Brands.embedText("CU", "씨유역삼점"))

		assertEquals("스타벅스", Brands.embedText("스타벅스", "스타벅스"))
		assertEquals("파리바게뜨 파리바게트논현점", Brands.embedText("파리바게뜨", "파리바게트논현점"))
	}

	@Test
	fun `split spellings of a brand collapse into one canonical form`() {
		assertEquals("CU", Brands.canonical("씨유역삼점"))
		assertEquals("CU", Brands.canonical("CU청담"))
		assertEquals("GS25", Brands.canonical("지에스25학동"))
		assertEquals("GS25", Brands.canonical("GS25역삼명진점"))
		assertEquals("파리바게뜨", Brands.canonical("파리바게트논현점"))
		assertEquals("서브웨이", Brands.canonical("써브웨이강남점"))
	}

	@Test
	fun `the search text contains every spelling`() {
		val text = Brands.searchText("CU")
		assertTrue("CU" in text && "씨유" in text, text)
	}

	@Test
	fun `a longer alias matches before a shorter one`() {
		assertEquals("매머드커피", Brands.canonical("매머드익스프레스역삼신한점"))
		assertEquals("매머드커피", Brands.canonical("매머드커피강남대로점"))
	}

	@Test
	fun `only a leading alias counts as a brand`() {
		assertNull(Brands.canonical("우리집CU앞분식"))
		assertNull(Brands.canonical("행복한커피빈스토리"), "not a brand when the alias comes later in the name")
	}

	@Test
	fun `a latin alias followed by more letters is a different word, not the brand`() {
		assertNull(Brands.canonical("Cut 0618"), "cut is its own word, not CU")
		assertNull(Brands.canonical("Cube건축적산사무소"))
		assertNull(Brands.canonical("cure약국"))
	}

	@Test
	fun `a latin alias still matches when what follows is not a letter`() {
		assertEquals("CU", Brands.canonical("CU역삼점"))
		assertEquals("CU", Brands.canonical("CU 강남대로점"))
		assertEquals("CU", Brands.canonical("CU"))
		assertEquals("이마트24", Brands.canonical("emart24역삼점"))
	}

	@Test
	fun `whitespace and letter case are ignored`() {
		assertEquals("CU", Brands.canonical("cu 역삼"))
		assertEquals("투썸플레이스", Brands.canonical("투썸 플레이스 강남"))
	}

	@Test
	fun `the recovered brand wins over the dictionary`() {
		assertEquals("스타벅스", Brands.resolve("스타벅스", "씨유역삼점"))
		assertEquals("CU", Brands.resolve(null, "씨유역삼점"))
		assertEquals("CU", Brands.resolve("  ", "씨유역삼점"), "a blank string is treated as absent")
	}

	@Test
	fun `a place that is not a brand resolves to null`() {
		assertNull(Brands.canonical("먹어도"))
		assertNull(Brands.canonical("혼밥대왕"))
		assertNull(Brands.canonical(""))
	}

	@Test
	fun `the branch name is appended before matching`() {
		assertEquals("CU", Brands.canonical("씨유", "역삼점"))
	}

	@Test
	fun `a name already starting with the brand is not prefixed twice in the label`() {
		assertEquals("CU역삼점", PlaceDocuments.label(row(name = "CU역삼점")))
		assertEquals("씨유역삼점", PlaceDocuments.label(row(name = "씨유역삼점")))
	}

	@Test
	fun `a brand missing from the name is put in front of the label`() {
		assertEquals("스타벅스 신사역", PlaceDocuments.label(row(name = "신사역", brand = "스타벅스")))
	}

	@Test
	fun `the seed has no duplicate spellings`() {
		val all = Brands.aliases.values.flatten().map { it.replace(" ", "").lowercase() }
		assertEquals(all.size, all.toSet().size, "duplicate spellings: ${all.groupBy { it }.filter { it.value.size > 1 }.keys}")
	}
}
