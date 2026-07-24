package dev.yubin.search.core.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 버전 도장 대조 규칙을 못박는다.
 *
 * 이 로직이 틀리면 두 가지로 틀린다. **느슨하면** 어긋난 색인 위에서 서비스가 뜨고(원래 막으려던
 * 사고가 그대로 난다), **빡빡하면** 정상 구성이 기동에 실패한다. 둘 다 배포 사고라 양쪽을 다 잰다.
 */
class IndexMetaTest {

	private val model = "multilingual-e5-small"

	@Test
	fun `같은 도장이면 통과한다`() {
		val stamp = IndexMeta.stamp(model, 384)
		assertEquals(IndexMeta.Verdict.Ok, IndexMeta.verify(stamp, stamp))
	}

	@Test
	fun `도장이 없으면 막지 않고 Missing 이다`() {
		// 분리 이전에 만든 인덱스로도 뜰 수 있어야 한다. 여기서 막으면 아무도 못 뜬다.
		assertEquals(IndexMeta.Verdict.Missing, IndexMeta.verify(null, IndexMeta.stamp()))
	}

	@Test
	fun `스키마 버전이 다르면 막는다`() {
		val indexed = IndexMeta.Stamp(schema_version = IndexMeta.SCHEMA_VERSION - 1)
		val verdict = IndexMeta.verify(indexed, IndexMeta.stamp())

		val mismatch = assertIs<IndexMeta.Verdict.Mismatch>(verdict)
		assertEquals(1, mismatch.differences.size)
		// 메시지에 양쪽 값이 다 있어야 무엇을 해야 할지 알 수 있다.
		assertTrue("${IndexMeta.SCHEMA_VERSION - 1}" in mismatch.differences[0], mismatch.differences[0])
		assertTrue("${IndexMeta.SCHEMA_VERSION}" in mismatch.differences[0], mismatch.differences[0])
	}

	@Test
	fun `임베딩 모델이 다르면 막는다`() {
		// 차원이 같아도 다른 모델이면 벡터가 다른 공간에 놓인다 — 엔진은 이걸 못 잡는다.
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
		// 하나만 알려주면 고치고 다시 뜨고 또 실패하는 걸 반복하게 된다.
		val indexed = IndexMeta.Stamp(IndexMeta.SCHEMA_VERSION - 1, "ko-sroberta", 768)
		val verdict = IndexMeta.verify(indexed, IndexMeta.stamp(model, 384))

		assertEquals(3, assertIs<IndexMeta.Verdict.Mismatch>(verdict).differences.size)
	}

	@Test
	fun `키워드 파이프라인은 임베딩 정보를 비교하지 않는다`() {
		// 키워드 인덱스의 도장에는 모델 정보가 없다(null). 질의기는 모델을 들고 있어도
		// 그걸 '다르다'로 보면 안 된다 — 정상 구성이 기동에 실패한다.
		val keywordStamp = IndexMeta.stamp()
		assertEquals(IndexMeta.Verdict.Ok, IndexMeta.verify(keywordStamp, IndexMeta.stamp(model, 384)))
	}

	@Test
	fun `벡터를 끄고 뜬 질의기는 벡터 도장과 부딪히지 않는다`() {
		// psp.vector.enabled=false 노드에는 EmbeddingModel 이 없다. 색인기가 남긴 벡터 도장을
		// 읽더라도 비교할 값이 없으니 통과해야 한다.
		assertEquals(
			IndexMeta.Verdict.Ok,
			IndexMeta.verify(IndexMeta.stamp(model, 384), IndexMeta.stamp()),
		)
	}
}
