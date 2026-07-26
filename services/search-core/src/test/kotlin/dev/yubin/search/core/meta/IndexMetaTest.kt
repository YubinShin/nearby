package dev.yubin.search.core.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IndexMetaTest {
	private val model = "multilingual-e5-small"

	@Test
	fun `같은 도장이면 통과한다`() {
		val stamp = IndexMeta.stamp(model, 384)
		assertEquals(IndexMeta.Verdict.Ok, IndexMeta.verify(stamp, stamp))
	}

	@Test
	fun `도장이 없으면 막지 않고 Missing 이다`() {
		assertEquals(IndexMeta.Verdict.Missing, IndexMeta.verify(null, IndexMeta.stamp()))
	}

	@Test
	fun `스키마 버전이 다르면 막는다`() {
		val indexed = IndexMeta.Stamp(schema_version = IndexMeta.SCHEMA_VERSION - 1)
		val verdict = IndexMeta.verify(indexed, IndexMeta.stamp())

		val mismatch = assertIs<IndexMeta.Verdict.Mismatch>(verdict)
		assertEquals(1, mismatch.differences.size)

		assertTrue("${IndexMeta.SCHEMA_VERSION - 1}" in mismatch.differences[0], mismatch.differences[0])
		assertTrue("${IndexMeta.SCHEMA_VERSION}" in mismatch.differences[0], mismatch.differences[0])
	}

	@Test
	fun `임베딩 모델이 다르면 막는다`() {
		val verdict = IndexMeta.verify(IndexMeta.stamp("ko-sroberta", 384), IndexMeta.stamp(model, 384))

		val mismatch = assertIs<IndexMeta.Verdict.Mismatch>(verdict)
		assertEquals(1, mismatch.differences.size)
		assertTrue("ko-sroberta" in mismatch.differences[0])
	}

	@Test
	fun `임베딩 차원이 다르면 막는다`() {
		val verdict = IndexMeta.verify(IndexMeta.stamp(model, 768), IndexMeta.stamp(model, 384))
		assertIs<IndexMeta.Verdict.Mismatch>(verdict)
	}

	@Test
	fun `어긋난 항목이 여럿이면 전부 담는다`() {
		val indexed = IndexMeta.Stamp(IndexMeta.SCHEMA_VERSION - 1, "ko-sroberta", 768)
		val verdict = IndexMeta.verify(indexed, IndexMeta.stamp(model, 384))

		assertEquals(3, assertIs<IndexMeta.Verdict.Mismatch>(verdict).differences.size)
	}

	@Test
	fun `키워드 파이프라인은 임베딩 정보를 비교하지 않는다`() {
		val keywordStamp = IndexMeta.stamp()
		assertEquals(IndexMeta.Verdict.Ok, IndexMeta.verify(keywordStamp, IndexMeta.stamp(model, 384)))
	}

	@Test
	fun `벡터를 끄고 뜬 질의기는 벡터 도장과 부딪히지 않는다`() {
		assertEquals(
			IndexMeta.Verdict.Ok,
			IndexMeta.verify(IndexMeta.stamp(model, 384), IndexMeta.stamp()),
		)
	}
}
