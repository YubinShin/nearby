package dev.yubin.search.core.meta

object IndexMeta {
	// 2: 중복 판정(place_duplicate)이 색인 대상에서 빠지기 시작한 판. 문서 모양은 그대로지만
	//    수록 대상이 달라졌다 — 증분으로는 이미 색인된 중복 문서를 걷어내지 못한다.
	const val SCHEMA_VERSION = 2

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

		data class Mismatch(val differences: List<String>) : Verdict
	}

	fun verify(actual: Stamp?, expected: Stamp): Verdict {
		if (actual == null) return Verdict.Missing

		val differences = buildList {
			if (actual.schema_version != expected.schema_version) {
				add("문서 스키마 버전: 색인=${actual.schema_version}, 질의=${expected.schema_version}")
			}
			compare("임베딩 모델", actual.embedding_model, expected.embedding_model)?.let(::add)
			compare("임베딩 차원", actual.embedding_dim, expected.embedding_dim)?.let(::add)
			compare("분석기 지문", actual.analyzer_fingerprint, expected.analyzer_fingerprint)?.let(::add)
		}
		return if (differences.isEmpty()) Verdict.Ok else Verdict.Mismatch(differences)
	}

	private fun compare(label: String, actual: Any?, expected: Any?): String? =
		if (actual != null && expected != null && actual != expected) {
			"$label: 색인=$actual, 질의=$expected"
		} else {
			null
		}
}
