package dev.yubin.search.core.analysis

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import java.security.MessageDigest

object AnalyzerFingerprint {
	const val SEARCH_ANALYZER = "komoran"

	const val SUGGEST_ANALYZER = "autocomplete_search"

	val PROBE: List<String> = listOf(
		"논현2동 투썸플레이스",
		"브런치빈강남 아메리카노",
		"역삼동 스타벅스커피 강남대로점",
	)

	fun of(es: ElasticsearchClient, index: String, analyzer: String): String? {
		val tokens = try {
			es.indices().analyze { a -> a.index(index).analyzer(analyzer).text(PROBE) }.tokens()
		} catch (e: ElasticsearchException) {
			if (e.status() == HTTP_NOT_FOUND) return null else throw e
		}
		return digest(tokens.map { "${it.token()}:${it.startOffset()}:${it.endOffset()}" })
	}

	fun digest(terms: List<String>): String =
		MessageDigest.getInstance("SHA-256")
			.digest(terms.joinToString("|").toByteArray(Charsets.UTF_8))
			.take(BYTES)
			.joinToString("") { "%02x".format(it.toInt() and 0xff) }

	private const val HTTP_NOT_FOUND = 404
	private const val BYTES = 6
}