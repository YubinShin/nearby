package dev.yubin.search.core.meta

object IndexMeta {
	const val SCHEMA_VERSION = 3

	const val ES_INDEX = "psp_index_meta"

	const val PIPELINE_SEARCH = "search"
	const val PIPELINE_SUGGEST = "suggest"
	const val PIPELINE_VECTOR = "vector"

	data class Stamp(
		val schema_version: Int = 0,
		val embedding_model: String? = null,
		val embedding_dim: Int? = null,
		val analyzer_fingerprint: String? = null,
	)

	fun stamp(
		embeddingModel: String? = null,
		embeddingDim: Int? = null,
		analyzerFingerprint: String? = null,
	) = Stamp(SCHEMA_VERSION, embeddingModel, embeddingDim, analyzerFingerprint)

	sealed interface Verdict {
		data object Ok : Verdict

		data object Missing : Verdict

		data class Mismatch(val differences: List<String>) : Verdict {
			fun sharesSchemaVersion() = differences.any { it.startsWith(SCHEMA_VERSION_LABEL) }
		}
	}

	private const val SCHEMA_VERSION_LABEL = "document schema version"

	fun verify(actual: Stamp?, expected: Stamp): Verdict {
		if (actual == null) return Verdict.Missing

		val differences = buildList {
			if (actual.schema_version != expected.schema_version) {
				add("$SCHEMA_VERSION_LABEL: indexed=${actual.schema_version}, querying=${expected.schema_version}")
			}
			compare("embedding model", actual.embedding_model, expected.embedding_model)?.let(::add)
			compare("embedding dim", actual.embedding_dim, expected.embedding_dim)?.let(::add)
			compare("analyzer fingerprint", actual.analyzer_fingerprint, expected.analyzer_fingerprint)?.let(::add)
		}
		return if (differences.isEmpty()) Verdict.Ok else Verdict.Mismatch(differences)
	}

	private fun compare(label: String, actual: Any?, expected: Any?): String? =
		if (actual != null && expected != null && actual != expected) {
			"$label: indexed=$actual, querying=$expected"
		} else {
			null
		}
}
