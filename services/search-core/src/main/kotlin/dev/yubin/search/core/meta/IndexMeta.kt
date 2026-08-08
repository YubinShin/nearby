package dev.yubin.search.core.meta

object IndexMeta {
	const val ES_INDEX = "psp_index_meta"

	const val PIPELINE_SEARCH = "search"
	const val PIPELINE_SUGGEST = "suggest"
	const val PIPELINE_VECTOR = "vector"

	data class Stamp(
		val document_fingerprint: String? = null,
		val brand_fingerprint: String? = null,
		val embedding_model: String? = null,
		val embedding_dim: Int? = null,
		val analyzer_fingerprint: String? = null,
	)

	fun stamp(
		documentFingerprint: String? = null,
		brandFingerprint: String? = null,
		embeddingModel: String? = null,
		embeddingDim: Int? = null,
		analyzerFingerprint: String? = null,
	) = Stamp(documentFingerprint, brandFingerprint, embeddingModel, embeddingDim, analyzerFingerprint)

	sealed interface Verdict {
		data object Ok : Verdict

		data object Missing : Verdict

		data class Mismatch(val differences: List<String>) : Verdict {
			fun sharesBrandDictionary() = differences.any { it.startsWith(BRAND_LABEL) }
		}
	}

	private const val DOCUMENT_LABEL = "document contract"
	private const val BRAND_LABEL = "brand dictionary"

	fun verify(actual: Stamp?, expected: Stamp): Verdict {
		if (actual == null) return Verdict.Missing

		val differences = buildList {
			compareDocument(actual.document_fingerprint, expected.document_fingerprint)?.let(::add)
			compare(BRAND_LABEL, actual.brand_fingerprint, expected.brand_fingerprint)?.let(::add)
			compare("embedding model", actual.embedding_model, expected.embedding_model)?.let(::add)
			compare("embedding dim", actual.embedding_dim, expected.embedding_dim)?.let(::add)
			compare("analyzer fingerprint", actual.analyzer_fingerprint, expected.analyzer_fingerprint)?.let(::add)
		}
		return if (differences.isEmpty()) Verdict.Ok else Verdict.Mismatch(differences)
	}

	private fun compareDocument(actual: String?, expected: String?): String? =
		if (expected != null && actual == null) {
			"$DOCUMENT_LABEL: indexed=none, querying=$expected"
		} else {
			compare(DOCUMENT_LABEL, actual, expected)
		}

	private fun compare(label: String, actual: Any?, expected: Any?): String? =
		if (actual != null && expected != null && actual != expected) {
			"$label: indexed=$actual, querying=$expected"
		} else {
			null
		}
}
