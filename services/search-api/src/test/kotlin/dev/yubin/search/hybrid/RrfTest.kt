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
	fun `두 채널이 모두 찾은 문서가 한 채널 1등보다 앞선다`() {
		val fused = Rrf.fuse(listOf(keyword("B", "A"), vector("C", "A")))

		assertEquals("A", fused.first().id, "합의가 단독 1등을 이겨야 한다 — 이게 RRF 를 쓰는 이유다")
		assertEquals(listOf("A", "B", "C"), ids(fused))
	}

	@Test
	fun `k 가 작으면 합의보다 단독 1등이 이긴다`() {
		val channels = listOf(keyword("B", "x", "x2", "x3", "A"), vector("y", "y2", "y3", "y4", "A"))

		assertEquals("B", Rrf.fuse(channels, k = 0).first().id)

		assertEquals("A", Rrf.fuse(channels, k = 60).first().id)
	}

	@Test
	fun `한 채널이 비어도 나머지 순서를 그대로 보존한다`() {
		val fused = Rrf.fuse(listOf(keyword("A", "B", "C"), vector()))

		assertEquals(listOf("A", "B", "C"), ids(fused))
	}

	@Test
	fun `양쪽 다 비면 결과도 비어 있다`() {
		assertTrue(Rrf.fuse(listOf(keyword(), vector())).isEmpty())
	}

	@Test
	fun `채널별 등수를 1-base 로 남기고 못 찾은 채널은 키가 없다`() {
		val fused = Rrf.fuse(listOf(keyword("A", "B"), vector("B")))

		val a = fused.single { it.id == "A" }
		val b = fused.single { it.id == "B" }
		assertEquals(mapOf("keyword" to 1), a.ranks, "벡터가 못 찾았으면 키 자체가 없어야 한다")
		assertEquals(mapOf("keyword" to 2, "vector" to 1), b.ranks)
	}

	@Test
	fun `가중치를 0 으로 주면 그 채널은 순위에 영향을 주지 못한다`() {
		val fused = Rrf.fuse(listOf(keyword("A"), vector("B", weight = 0.0)))

		assertEquals(listOf("A", "B"), ids(fused))
		assertEquals(0.0, fused.single { it.id == "B" }.score)
	}

	@Test
	fun `가중치를 높인 채널의 등수가 더 세게 반영된다`() {
		val even = Rrf.fuse(listOf(keyword("K"), vector("V")))
		val vectorHeavy = Rrf.fuse(listOf(keyword("K"), vector("V", weight = 2.0)))

		assertEquals("K", even.first().id, "동점일 땐 id 순으로 결정적이어야 한다")
		assertEquals("V", vectorHeavy.first().id)
	}

	@Test
	fun `점수가 같으면 항상 같은 순서가 나온다`() {
		val once = Rrf.fuse(listOf(keyword("B", "A"), vector("A", "B")))
		val again = Rrf.fuse(listOf(vector("A", "B"), keyword("B", "A")))

		assertEquals(ids(once), ids(again), "채널을 넣은 순서가 결과 순서를 바꾸면 안 된다")
		assertEquals(listOf("A", "B"), ids(once))
	}

	@Test
	fun `같은 채널이 같은 문서를 두 번 줘도 한 번만 센다`() {
		val fused = Rrf.fuse(listOf(keyword("A", "A", "B")))

		assertEquals(listOf("A", "B"), ids(fused))
		assertEquals(mapOf("keyword" to 1), fused.first().ranks, "첫 등수만 인정한다")
		assertEquals(1.0 / 61, fused.first().score)
	}

	@Test
	fun `점수 스케일이 아니라 등수만 쓴다`() {
		val fused = Rrf.fuse(listOf(keyword("A", "B"), vector("B", "A")))

		val a = fused.single { it.id == "A" }
		val b = fused.single { it.id == "B" }
		assertEquals(a.score, b.score, "1등+2등 과 2등+1등 은 같은 점수여야 한다")
	}

	@Test
	fun `깊은 후보를 가져와야 결합의 이득이 생긴다`() {
		val deep = (1..50).map { "K$it" }
		val fused = Rrf.fuse(listOf(Rrf.Channel("keyword", deep), vector("K50")))

		assertEquals("K50", fused.first().id, "두 채널이 함께 가리키면 단독 1등(K1)을 넘어선다")

		val shallow = Rrf.fuse(listOf(Rrf.Channel("keyword", deep.take(10)), vector("K50")))
		assertEquals(1, shallow.single { it.id == "K50" }.ranks.size, "한 채널에서만 걸린 셈이 된다")
		assertTrue(shallow.first().id == "K1")
	}
}
