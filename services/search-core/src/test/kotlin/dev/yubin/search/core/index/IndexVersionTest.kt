package dev.yubin.search.core.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexVersionTest {
	@Test
	fun `newName has the alias_14digit shape and is read back by tokenOf`() {
		val name = IndexVersion.newName("place_search")
		assertTrue(Regex("^place_search_\\d{14}$").matches(name), "shape does not match: $name")
		assertEquals(14, IndexVersion.tokenOf("place_search", name)?.length)
	}

	@Test
	fun `tokenOf extracts a timestamp only from names matching the rule`() {
		assertEquals("20260725143022", IndexVersion.tokenOf("place_search", "place_search_20260725143022"))
		assertNull(IndexVersion.tokenOf("place_search", "place_search_v1"))
		assertNull(IndexVersion.tokenOf("place_search", "place_search_2026072514"))
		assertNull(IndexVersion.tokenOf("place_vec", "place_search_20260725143022"))
	}

	@Test
	fun `a later timestamp always compares greater as a string (reconcile invariant)`() {
		val older = "20260725090000"
		val newer = "20260725143022"
		val nextDay = "20260726000000"
		assertTrue(newer > older)
		assertTrue(nextDay > newer)
		assertEquals(nextDay, listOf(older, newer, nextDay).maxOrNull())
	}
}
