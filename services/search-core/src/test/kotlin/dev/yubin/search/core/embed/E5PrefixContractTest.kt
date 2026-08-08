package dev.yubin.search.core.embed

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
