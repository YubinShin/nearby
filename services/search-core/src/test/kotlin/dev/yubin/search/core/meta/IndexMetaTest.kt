package dev.yubin.search.core.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IndexMetaTest {
	private val model = "multilingual-e5-small"
	private val document = "a1b2c3d4e5f6"

	@Test
	fun `an identical stamp passes`() {
		val stamp = IndexMeta.stamp(document, embeddingModel = model, embeddingDim = 384)
		assertEquals(IndexMeta.Verdict.Ok, IndexMeta.verify(stamp, stamp))
	}

	@Test
	fun `a missing stamp reports Missing instead of blocking`() {
		assertEquals(IndexMeta.Verdict.Missing, IndexMeta.verify(null, IndexMeta.stamp()))
	}

	@Test
	fun `a different document fingerprint blocks`() {
		val verdict = IndexMeta.verify(IndexMeta.stamp("0f1e2d3c4b5a"), IndexMeta.stamp(document))

		val mismatch = assertIs<IndexMeta.Verdict.Mismatch>(verdict)
		assertEquals(1, mismatch.differences.size)
		assertTrue("0f1e2d3c4b5a" in mismatch.differences[0], mismatch.differences[0])
		assertTrue(document in mismatch.differences[0], mismatch.differences[0])
	}

	@Test
	fun `an index stamped before the document fingerprint existed blocks`() {
		val verdict = IndexMeta.verify(IndexMeta.Stamp(), IndexMeta.stamp(document))

		val mismatch = assertIs<IndexMeta.Verdict.Mismatch>(verdict)
		assertEquals(1, mismatch.differences.size)
		assertTrue("indexed=none" in mismatch.differences[0], mismatch.differences[0])
	}

	@Test
	fun `a different brand dictionary blocks and reports a shared cause`() {
		val verdict = IndexMeta.verify(
			IndexMeta.stamp(document, brandFingerprint = "111111111111"),
			IndexMeta.stamp(document, brandFingerprint = "222222222222"),
		)

		val mismatch = assertIs<IndexMeta.Verdict.Mismatch>(verdict)
		assertEquals(1, mismatch.differences.size)
		assertTrue(mismatch.sharesBrandDictionary(), mismatch.differences[0])
	}

	@Test
	fun `a fingerprint difference outside the brand dictionary is not a shared cause`() {
		val verdict = IndexMeta.verify(IndexMeta.stamp("0f1e2d3c4b5a"), IndexMeta.stamp(document))

		assertTrue(!assertIs<IndexMeta.Verdict.Mismatch>(verdict).sharesBrandDictionary())
	}

	@Test
	fun `a different embedding model blocks`() {
		val verdict = IndexMeta.verify(
			IndexMeta.stamp(document, embeddingModel = "ko-sroberta", embeddingDim = 384),
			IndexMeta.stamp(document, embeddingModel = model, embeddingDim = 384),
		)

		val mismatch = assertIs<IndexMeta.Verdict.Mismatch>(verdict)
		assertEquals(1, mismatch.differences.size)
		assertTrue("ko-sroberta" in mismatch.differences[0])
	}

	@Test
	fun `a different embedding dimension blocks`() {
		val verdict = IndexMeta.verify(
			IndexMeta.stamp(document, embeddingModel = model, embeddingDim = 768),
			IndexMeta.stamp(document, embeddingModel = model, embeddingDim = 384),
		)
		assertIs<IndexMeta.Verdict.Mismatch>(verdict)
	}

	@Test
	fun `every mismatched field is collected`() {
		val indexed = IndexMeta.stamp("0f1e2d3c4b5a", "111111111111", "ko-sroberta", 768)
		val verdict = IndexMeta.verify(indexed, IndexMeta.stamp(document, "222222222222", model, 384))

		assertEquals(4, assertIs<IndexMeta.Verdict.Mismatch>(verdict).differences.size)
	}

	@Test
	fun `the keyword pipeline does not compare embedding information`() {
		val keywordStamp = IndexMeta.stamp(document)
		val verdict = IndexMeta.verify(keywordStamp, IndexMeta.stamp(document, embeddingModel = model, embeddingDim = 384))

		assertEquals(IndexMeta.Verdict.Ok, verdict)
	}

	@Test
	fun `an index stamped before the analyzer fingerprint existed does not block`() {
		val indexed = IndexMeta.stamp(document)
		val verdict = IndexMeta.verify(indexed, IndexMeta.stamp(document, analyzerFingerprint = "a1b2c3d4e5f6"))

		assertEquals(IndexMeta.Verdict.Ok, verdict)
	}

	@Test
	fun `a different analyzer fingerprint blocks`() {
		val indexed = IndexMeta.stamp(document, analyzerFingerprint = "a1b2c3d4e5f6")
		val verdict = IndexMeta.verify(indexed, IndexMeta.stamp(document, analyzerFingerprint = "0f1e2d3c4b5a"))

		val mismatch = assertIs<IndexMeta.Verdict.Mismatch>(verdict)
		assertEquals(1, mismatch.differences.size)
		assertTrue("a1b2c3d4e5f6" in mismatch.differences[0], mismatch.differences[0])
	}

	@Test
	fun `a querier started with vectors off does not clash with a vector stamp`() {
		assertEquals(
			IndexMeta.Verdict.Ok,
			IndexMeta.verify(IndexMeta.stamp(document, embeddingModel = model, embeddingDim = 384), IndexMeta.stamp()),
		)
	}
}
