package dev.yubin.search.core.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IndexMetaTest {
	private val model = "multilingual-e5-small"

	@Test
	fun `an identical stamp passes`() {
		val stamp = IndexMeta.stamp(model, 384)
		assertEquals(IndexMeta.Verdict.Ok, IndexMeta.verify(stamp, stamp))
	}

	@Test
	fun `a missing stamp reports Missing instead of blocking`() {
		assertEquals(IndexMeta.Verdict.Missing, IndexMeta.verify(null, IndexMeta.stamp()))
	}

	@Test
	fun `a different schema version blocks`() {
		val indexed = IndexMeta.Stamp(schema_version = IndexMeta.SCHEMA_VERSION - 1)
		val verdict = IndexMeta.verify(indexed, IndexMeta.stamp())

		val mismatch = assertIs<IndexMeta.Verdict.Mismatch>(verdict)
		assertEquals(1, mismatch.differences.size)

		assertTrue("${IndexMeta.SCHEMA_VERSION - 1}" in mismatch.differences[0], mismatch.differences[0])
		assertTrue("${IndexMeta.SCHEMA_VERSION}" in mismatch.differences[0], mismatch.differences[0])
	}

	@Test
	fun `a different embedding model blocks`() {
		val verdict = IndexMeta.verify(IndexMeta.stamp("ko-sroberta", 384), IndexMeta.stamp(model, 384))

		val mismatch = assertIs<IndexMeta.Verdict.Mismatch>(verdict)
		assertEquals(1, mismatch.differences.size)
		assertTrue("ko-sroberta" in mismatch.differences[0])
	}

	@Test
	fun `a different embedding dimension blocks`() {
		val verdict = IndexMeta.verify(IndexMeta.stamp(model, 768), IndexMeta.stamp(model, 384))
		assertIs<IndexMeta.Verdict.Mismatch>(verdict)
	}

	@Test
	fun `every mismatched field is collected`() {
		val indexed = IndexMeta.Stamp(IndexMeta.SCHEMA_VERSION - 1, "ko-sroberta", 768)
		val verdict = IndexMeta.verify(indexed, IndexMeta.stamp(model, 384))

		assertEquals(3, assertIs<IndexMeta.Verdict.Mismatch>(verdict).differences.size)
	}

	@Test
	fun `the keyword pipeline does not compare embedding information`() {
		val keywordStamp = IndexMeta.stamp()
		assertEquals(IndexMeta.Verdict.Ok, IndexMeta.verify(keywordStamp, IndexMeta.stamp(model, 384)))
	}

	@Test
	fun `a querier started with vectors off does not clash with a vector stamp`() {
		assertEquals(
			IndexMeta.Verdict.Ok,
			IndexMeta.verify(IndexMeta.stamp(model, 384), IndexMeta.stamp()),
		)
	}
}
