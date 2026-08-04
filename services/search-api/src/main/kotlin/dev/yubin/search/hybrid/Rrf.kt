package dev.yubin.search.hybrid

object Rrf {
	data class Channel(val name: String, val ranking: List<String>, val weight: Double = 1.0)

	data class Fused(val id: String, val score: Double, val ranks: Map<String, Int>)

	const val DEFAULT_K = 60

	fun fuse(channels: List<Channel>, k: Int = DEFAULT_K): List<Fused> {
		val scores = LinkedHashMap<String, Double>()
		val ranks = LinkedHashMap<String, MutableMap<String, Int>>()

		for (channel in channels) {
			channel.ranking.forEachIndexed { index, id ->
				val rank = index + 1
				val seen = ranks.getOrPut(id) { LinkedHashMap() }
				if (seen.putIfAbsent(channel.name, rank) == null) {
					scores.merge(id, channel.weight / (k + rank), Double::plus)
				}
			}
		}

		return scores.entries
			.map { (id, score) -> Fused(id, score, ranks[id].orEmpty()) }
			.sortedWith(
				compareByDescending<Fused> { it.score }
					.thenBy { it.ranks.values.minOrNull() ?: Int.MAX_VALUE }
					.thenBy { it.id },
			)
	}
}
