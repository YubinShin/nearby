package dev.yubin.search.core.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AnalyzerFingerprintTest {
	private val tokens = listOf("논현:0:2", "동:2:3", "투썸플레이스:4:10")

	@Test
	fun `the same token stream always digests to the same value`() {
		assertEquals(Digest.of(tokens), Digest.of(tokens))
	}

	@Test
	fun `a dictionary change that splits differently changes the digest`() {
		val split = listOf("놓:0:1", "ㄴ:1:2", "현:2:3", "투썸플레이스:4:10")
		assertNotEquals(Digest.of(tokens), Digest.of(split))
	}

	@Test
	fun `the same terms at different offsets digest differently`() {
		val shifted = listOf("논현:0:2", "동:2:3", "투썸플레이스:5:11")
		assertNotEquals(Digest.of(tokens), Digest.of(shifted))
	}

	@Test
	fun `a dropped token changes the digest`() {
		assertNotEquals(Digest.of(tokens), Digest.of(tokens.dropLast(1)))
	}

	@Test
	fun `the digest is twelve hex characters`() {
		val digest = Digest.of(tokens)
		assertEquals(12, digest.length, digest)
		assertTrue(digest.all { it in "0123456789abcdef" }, digest)
	}

	@Test
	fun `an empty token stream still digests`() {
		assertEquals(12, Digest.of(emptyList()).length)
	}
}
