package dev.yubin.search.core.embed

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * multilingual-e5 는 질의와 문서에 서로 다른 접두어를 요구한다. 색인과 질의가 같은 접두어를 쓰면
 * 두 벡터가 다른 공간에 놓여 검색이 오류 없이 조용히 무너진다.
 *
 * [EmbeddingModelTest] 는 모델 파일이 있어야 돌아 CI 에서 건너뛴다. 접두어 문자열 자체는
 * 모델 없이 확인할 수 있으므로 여기서 고정한다.
 */
class E5PrefixContractTest {
	@Test
	fun `the prefixes are the ones multilingual-e5 was trained with`() {
		assertEquals("query: ", EmbeddingModel.QUERY_PREFIX)
		assertEquals("passage: ", EmbeddingModel.PASSAGE_PREFIX)
	}

	@Test
	fun `the two prefixes differ`() {
		assertNotEquals(EmbeddingModel.QUERY_PREFIX, EmbeddingModel.PASSAGE_PREFIX)
	}

	@Test
	fun `each prefix ends with the separating space`() {
		assertEquals(' ', EmbeddingModel.QUERY_PREFIX.last())
		assertEquals(' ', EmbeddingModel.PASSAGE_PREFIX.last())
	}
}
