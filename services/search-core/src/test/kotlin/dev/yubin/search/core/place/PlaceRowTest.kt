package dev.yubin.search.core.place

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceRowTest {
	private fun row(
		deletedAt: OffsetDateTime? = null,
		duplicateOf: String? = null,
	) = PlaceRow(
		placeId = "MA0106202506A1843320", name = "커피인류 역삼점", branch = null, brand = null,
		categoryLarge = "음식", categoryMid = "비알코올", categorySmall = "카페",
		sido = "서울특별시", sigungu = "강남구", dong = "역삼동",
		jibunAddress = "역삼동 123-4", roadAddress = "테헤란로 1",
		lon = 127.047994, lat = 37.498557,
		updatedAt = OffsetDateTime.parse("2026-07-22T00:00:00Z"),
		deletedAt = deletedAt, duplicateOf = duplicateOf,
	)

	@Test
	fun `a live row that is nobody's duplicate is indexable`() {
		assertTrue(row().indexable)
	}

	@Test
	fun `a soft-deleted row is not indexable`() {
		assertFalse(row(deletedAt = OffsetDateTime.parse("2026-07-30T00:00:00Z")).indexable)
	}

	@Test
	fun `a row judged duplicate is not indexable even though it is alive`() {
		assertFalse(row(duplicateOf = "MA0101202504A0077166").indexable)
	}

	@Test
	fun `duplicateOf defaults to null so a row is indexable unless judged`() {
		assertTrue(row().duplicateOf == null)
	}
}
