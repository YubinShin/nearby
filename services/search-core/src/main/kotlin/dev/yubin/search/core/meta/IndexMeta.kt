package dev.yubin.search.core.meta

object IndexMeta {
	// 2: 중복 판정(place_duplicate)이 색인 대상에서 빠지기 시작한 판. 문서 모양은 그대로지만
	//    수록 대상이 달라졌다 — 증분으로는 이미 색인된 중복 문서를 걷어내지 못한다.
	// 3: suggest 문서에 brand_text, Qdrant payload 에 category_mid·updated_at 이 늘었다.
	//    옛 색인에는 그 필드가 없어 질의가 조용히 매칭을 잃는다.
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
