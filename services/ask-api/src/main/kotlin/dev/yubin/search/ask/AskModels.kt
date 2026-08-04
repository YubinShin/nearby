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
)

data class AskResponse(
	val query: String,
	val parsed: ParsedQuery?,
	val applied: SearchRequestPlan,
	val degraded: Boolean,
	val degradedBy: List<String>,
	val llmTookMs: Long,
	val searchTookMs: Long,
	val tookMs: Long,
	val search: JsonNode,
)
