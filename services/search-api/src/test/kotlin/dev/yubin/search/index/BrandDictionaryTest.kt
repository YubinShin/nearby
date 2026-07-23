package dev.yubin.search.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 사람이 관리하는 브랜드 시드(`brands.tsv`)의 규칙을 엔진 없이 못박는다. */
class BrandDictionaryTest {

	@Test
	fun `표기가 갈린 브랜드를 하나로 모은다`() {
		// 이 파일의 존재 이유. 실측: 씨유 176건 / CU 11건으로 갈려 있었다.
		assertEquals("CU", BrandDictionary.canonical("씨유역삼점"))
		assertEquals("CU", BrandDictionary.canonical("CU청담"))
		assertEquals("GS25", BrandDictionary.canonical("지에스25학동"))
		assertEquals("GS25", BrandDictionary.canonical("GS25역삼명진점"))
		assertEquals("파리바게뜨", BrandDictionary.canonical("파리바게트논현점"))
		assertEquals("서브웨이", BrandDictionary.canonical("써브웨이강남점"))
	}

	@Test
	fun `검색용 문자열엔 모든 표기가 들어간다`() {
		// 색인에 한 표기만 넣으면 다른 표기로 친 사용자가 통째로 놓친다.
		val text = BrandDictionary.searchText("CU")
		assertTrue("CU" in text && "씨유" in text, text)
	}

	@Test
	fun `긴 표기가 짧은 표기보다 먼저 매칭된다`() {
		// '매머드'와 '매머드커피'가 둘 다 등재돼 있다. 짧은 쪽이 먼저 먹으면 정규형이 흔들린다.
		assertEquals("매머드커피", BrandDictionary.canonical("매머드익스프레스역삼신한점"))
		assertEquals("매머드커피", BrandDictionary.canonical("매머드커피강남대로점"))
	}

	@Test
	fun `이름 앞에 와야 브랜드로 본다`() {
		// '포함'으로 넓히면 엉뚱한 가게가 딸려 온다. 프랜차이즈 상호는 브랜드가 앞에 온다.
		assertNull(BrandDictionary.canonical("우리집CU앞분식"))
		assertNull(BrandDictionary.canonical("행복한커피빈스토리"), "커피빈이 뒤에 있으면 아니다")
	}

	@Test
	fun `공백과 대소문자는 무시한다`() {
		assertEquals("CU", BrandDictionary.canonical("cu 역삼"))
		assertEquals("투썸플레이스", BrandDictionary.canonical("투썸 플레이스 강남"))
	}

	@Test
	fun `브랜드가 아닌 가게는 null 이다`() {
		assertNull(BrandDictionary.canonical("먹어도"))
		assertNull(BrandDictionary.canonical("혼밥대왕"))
		assertNull(BrandDictionary.canonical(""))
	}

	@Test
	fun `지점명까지 이어 붙여 본다`() {
		// 원천이 상호명과 지점명을 나눠 담는다 — '씨유' + '역삼점'
		assertEquals("CU", BrandDictionary.canonical("씨유", "역삼점"))
	}

	@Test
	fun `시드에 중복 표기가 없다`() {
		// 같은 표기가 두 브랜드에 걸리면 어느 쪽이 이길지 파일 순서에 좌우된다.
		val all = BrandDictionary.aliases.values.flatten().map { it.replace(" ", "").lowercase() }
		assertEquals(all.size, all.toSet().size, "중복 표기: ${all.groupBy { it }.filter { it.value.size > 1 }.keys}")
	}
}
