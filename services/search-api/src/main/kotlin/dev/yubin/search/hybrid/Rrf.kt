package dev.yubin.search.hybrid

/**
 * RRF(Reciprocal Rank Fusion) — 서로 다른 엔진의 결과를 **순위만 보고** 합친다 (ADR 0003).
 *
 * 왜 점수가 아니라 순위인가: BM25 점수는 몇십까지 뛰고 코사인 유사도는 0~1 안에 갇혀 있다.
 * 그냥 더하면 스케일이 큰 쪽이 결과를 독식한다. 정규화(min-max, z-score)로 맞추는 방법도 있지만
 * 질의마다 분포가 달라서 **질의별로 기준이 흔들린다.** 순위는 어떤 질의에서도 1등이 1등이다.
 *
 *     score(d) = Σ_channel  weight_c / (k + rank_c(d))
 *
 * `k` 는 상위권의 영향력을 눌러 주는 완충값이다. `k=0` 이면 1등(1/1)과 2등(1/2)의 차이가 2배라
 * 한 채널의 1등이 나머지 전부를 이겨 버린다. `k=60` 이면 1/61 vs 1/62 로 거의 붙어 있어서,
 * **"둘 다 찾았다"가 "한쪽에서 1등"보다 세진다.** 하이브리드에서 원하는 성질이 정확히 이것이다.
 *
 * 순수 함수라 엔진 없이 단위 테스트로 성질을 고정할 수 있다 (`RrfTest`).
 */
object Rrf {

	/** 한 채널의 결과 순위. [ranking] 은 1등부터 순서대로 놓인 문서 id 목록. */
	data class Channel(val name: String, val ranking: List<String>, val weight: Double = 1.0)

	/** 결합 결과 한 건. [ranks] 는 각 채널에서 몇 등이었는지 (1-base, 없으면 키 자체가 없다). */
	data class Fused(val id: String, val score: Double, val ranks: Map<String, Int>)

	const val DEFAULT_K = 60

	fun fuse(channels: List<Channel>, k: Int = DEFAULT_K): List<Fused> {
		val scores = LinkedHashMap<String, Double>()
		val ranks = LinkedHashMap<String, MutableMap<String, Int>>()

		for (channel in channels) {
			channel.ranking.forEachIndexed { index, id ->
				val rank = index + 1
				// 같은 채널이 같은 문서를 두 번 주면(있어선 안 되지만) 첫 등수만 인정한다.
				val seen = ranks.getOrPut(id) { LinkedHashMap() }
				// put 이 아니라 putIfAbsent — put 은 점수를 안 더하면서 등수만 덮어쓴다.
				if (seen.putIfAbsent(channel.name, rank) == null) {
					scores.merge(id, channel.weight / (k + rank), Double::plus)
				}
			}
		}

		return scores.entries
			.map { (id, score) -> Fused(id, score, ranks[id].orEmpty()) }
			// 동점 처리를 정해두지 않으면 **페이지마다 순서가 흔들려** 같은 문서가 두 번 보이거나
			// 아예 안 보인다. 점수 → 최고 등수 → id 순으로 완전히 결정적인 순서를 만든다.
			.sortedWith(
				compareByDescending<Fused> { it.score }
					.thenBy { it.ranks.values.minOrNull() ?: Int.MAX_VALUE }
					.thenBy { it.id },
			)
	}
}
