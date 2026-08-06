package dev.yubin.search.indexer.batch

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadProgressTest {
	private val lag = Duration.ofSeconds(60)
	private val base = OffsetDateTime.of(2026, 8, 3, 12, 0, 0, 0, ZoneOffset.UTC)

	private fun at(second: Long) = base.plusSeconds(second)

	@Test
	fun `a watermark older than the ceiling advances unchanged`() {
		assertEquals(at(0), LoadProgress.capWatermark(at(0), at(90), lag))
	}

	@Test
	fun `a watermark newer than the ceiling is held back to the ceiling`() {
		assertEquals(at(30), LoadProgress.capWatermark(at(50), at(90), lag))
	}

	@Test
	fun `a watermark exactly on the ceiling advances unchanged`() {
		assertEquals(at(30), LoadProgress.capWatermark(at(30), at(90), lag))
	}

	@Test
	fun `no rows read leaves the watermark absent`() {
		assertNull(LoadProgress.capWatermark(null, at(90), lag))
	}

	@Test
	fun `a row stamped at transaction start stays above the advanced watermark`() {
		val transactionStart = at(20)
		val rowsReadUpTo = at(50)

		val advanced = LoadProgress.capWatermark(rowsReadUpTo, at(60), lag)!!

		assertTrue(transactionStart.isAfter(advanced))
	}
}
