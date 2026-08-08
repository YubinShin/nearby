package dev.yubin.search.hybrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RrfTest {
	private fun keyword(vararg ids: String, weight: Double = 1.0) =
		Rrf.Channel("keyword", ids.toList(), weight)

	private fun vector(vararg ids: String, weight: Double = 1.0) =
		Rrf.Channel("vector", ids.toList(), weight)

	private fun ids(fused: List<Rrf.Fused>) = fused.map { it.id }

	@Test
	fun `a document found by both channels outranks a single channel top hit`() {
		val fused = Rrf.fuse(listOf(keyword("B", "A"), vector("C", "A")))

		assertEquals("A", fused.first().id, "consensus must beat a single top hit — this is why we use RRF")
		assertEquals(listOf("A", "B", "C"), ids(fused))
	}

	@Test
	fun `a small k lets a single channel top hit beat consensus`() {
		val channels = listOf(keyword("B", "x", "x2", "x3", "A"), vector("y", "y2", "y3", "y4", "A"))

		assertEquals("B", Rrf.fuse(channels, k = 0).first().id)
		assertEquals("A", Rrf.fuse(channels, k = 60).first().id)
	}

	@Test
	fun `an empty channel preserves the order of the other one`() {
		val fused = Rrf.fuse(listOf(keyword("A", "B", "C"), vector()))

		assertEquals(listOf("A", "B", "C"), ids(fused))
	}

	@Test
	fun `both channels empty yields an empty result`() {
		assertTrue(Rrf.fuse(listOf(keyword(), vector())).isEmpty())
	}

	@Test
	fun `per channel ranks are recorded 1-based and a channel that missed has no key`() {
		val fused = Rrf.fuse(listOf(keyword("A", "B"), vector("B")))

		val a = fused.single { it.id == "A" }
		val b = fused.single { it.id == "B" }
		assertEquals(mapOf("keyword" to 1), a.ranks, "if the vector channel missed it, the key must not exist at all")
		assertEquals(mapOf("keyword" to 2, "vector" to 1), b.ranks)
	}

	@Test
	fun `a channel weighted 0 adds no score but still contributes candidates`() {
		val fused = Rrf.fuse(listOf(keyword("A"), vector("B", weight = 0.0)))

		assertEquals(listOf("A", "B"), ids(fused), "B is still in the result — weight 0 does not disable the channel")
		assertEquals(0.0, fused.single { it.id == "B" }.score)
		assertEquals(mapOf("vector" to 1), fused.single { it.id == "B" }.ranks)
	}

	@Test
	fun `ranks from a channel with a raised weight count more strongly`() {
		val even = Rrf.fuse(listOf(keyword("K"), vector("V")))
		val vectorHeavy = Rrf.fuse(listOf(keyword("K"), vector("V", weight = 2.0)))

		assertEquals("K", even.first().id, "ties must break deterministically by id")
		assertEquals("V", vectorHeavy.first().id)
	}

	@Test
	fun `equal scores always produce the same order`() {
		val once = Rrf.fuse(listOf(keyword("B", "A"), vector("A", "B")))
		val again = Rrf.fuse(listOf(vector("A", "B"), keyword("B", "A")))

		assertEquals(ids(once), ids(again), "the order channels are passed in must not change the result order")
		assertEquals(listOf("A", "B"), ids(once))
	}

	@Test
	fun `the same document twice from one channel counts once`() {
		val fused = Rrf.fuse(listOf(keyword("A", "A", "B")))

		assertEquals(listOf("A", "B"), ids(fused))
		assertEquals(mapOf("keyword" to 1), fused.first().ranks, "only the first rank counts")
		assertEquals(1.0 / 61, fused.first().score)
	}

	@Test
	fun `a better rank scores higher and the gap narrows as ranks deepen`() {
		val fused = Rrf.fuse(listOf(keyword("A", "B", "C")))

		val (a, b, c) = listOf("A", "B", "C").map { id -> fused.single { it.id == id }.score }

		assertTrue(a > b && b > c, "a better rank must score higher: $a $b $c")
		assertTrue(a - b > b - c, "RRF flattens as ranks deepen, so the first gap is the widest")
	}

	@Test
	fun `fusion only pays off with a deep candidate pool`() {
		val deep = (1..50).map { "K$it" }
		val fused = Rrf.fuse(listOf(Rrf.Channel("keyword", deep), vector("K50")))

		assertEquals("K50", fused.first().id, "when both channels point at it, it beats the single top hit (K1)")

		val shallow = Rrf.fuse(listOf(Rrf.Channel("keyword", deep.take(10)), vector("K50")))
		assertEquals(1, shallow.single { it.id == "K50" }.ranks.size, "it ends up matched by only one channel")
		assertTrue(shallow.first().id == "K1")
	}
}
