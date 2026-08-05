package dev.yubin.search.ask

import tools.jackson.databind.JsonNode

data class ParsedQuery(
	val keyword: String,
	val categoryHint: String? = null,
	val geoAnchor: String? = null,
	val radiusM: Int? = null,
	val expectsEmpty: Boolean = false,
)

data class SearchRequestPlan(
	val q: String,
	val size: Int,
	val lat: Double? = null,
	val lon: Double? = null,
	val radius: Int? = null,
	val unmapped: List<String> = emptyList(),
	val unsupported: List<String> = emptyList(),
)

data class AnswerSentence(
	val text: String,
	val evidence: List<String> = emptyList(),
)

data class Answer(
	val found: Boolean,
	val unverifiableConditions: List<String> = emptyList(),
	val sentences: List<AnswerSentence> = emptyList(),
	val droppedEvidence: List<String> = emptyList(),
	val driftingEvidence: List<String> = emptyList(),
	val leakedTerms: List<String> = emptyList(),
)

data class AskResponse(
	val query: String,
	val parsed: ParsedQuery?,
	val applied: SearchRequestPlan,
	val degraded: Boolean,
	val degradedBy: List<String>,
	val llmVendor: String,
	val llmTookMs: Long,
	val searchTookMs: Long,
	val answerTookMs: Long,
	val tookMs: Long,
	val search: JsonNode,
	val answer: Answer? = null,
)
