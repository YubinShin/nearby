package dev.yubin.search.core.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexVersionTest {
	@Test
	fun `newName 은 alias_14자리 형식이고 tokenOf 로 되읽힌다`() {
		val name = IndexVersion.newName("place_search")
		assertTrue(Regex("^place_search_\\d{14}$").matches(name), "형식이 어긋남: $name")
		assertEquals(14, IndexVersion.tokenOf("place_search", name)?.length)
	}

	@Test
	fun `tokenOf 는 규칙에 맞는 이름의 타임스탬프만 뽑는다`() {
		assertEquals("20260725143022", IndexVersion.tokenOf("place_search", "place_search_20260725143022"))

		assertNull(IndexVersion.tokenOf("place_search", "place_search_v1"))

		assertNull(IndexVersion.tokenOf("place_search", "place_search_2026072514"))

		assertNull(IndexVersion.tokenOf("place_vec", "place_search_20260725143022"))
	}

	@Test
	fun `나중 타임스탬프는 문자열 비교에서 항상 더 크다 (reconcile 불변식)`() {
		val older = "20260725090000"
		val newer = "20260725143022"
		val nextDay = "20260726000000"
		assertTrue(newer > older)
		assertTrue(nextDay > newer)

		assertEquals(nextDay, listOf(older, newer, nextDay).maxOrNull())
	}
}
