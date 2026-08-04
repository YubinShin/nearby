package dev.yubin.search.ask.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import dev.yubin.search.ask.ParsedQuery
import tools.jackson.databind.ObjectMapper

object GeminiWire {
	fun decode(response: GeminiResponse, mapper: ObjectMapper): ParsedQuery {
		val candidate = response.candidates.firstOrNull()
			?: throw LlmException("gemini returned no candidate (promptFeedback=${response.promptFeedback})")
		val text = candidate.content?.parts?.firstNotNullOfOrNull { it.text?.ifBlank { null } }
			?: throw LlmException("gemini candidate carried no text (finishReason=${candidate.finishReason})")

		val parsed = try {
			mapper.readValue(text, ParsedQueryWire::class.java)
		} catch (e: Exception) {
			throw LlmException("gemini returned a payload that does not match the response schema: $text", e)
		}
		if (parsed.keyword.isNullOrBlank()) {
			throw LlmException("gemini returned an empty keyword: $text")
		}
		return ParsedQuery(
			keyword = parsed.keyword.trim(),
			categoryHint = parsed.categoryHint?.trim()?.ifBlank { null },
			geoAnchor = parsed.geoAnchor?.trim()?.ifBlank { null },
			radiusM = parsed.radiusM,
			expectsEmpty = parsed.expectsEmpty,
		)
	}
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GeminiResponse(
	val candidates: List<GeminiCandidate> = emptyList(),
	val promptFeedback: Map<String, Any?>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GeminiCandidate(
	val content: GeminiContent? = null,
	val finishReason: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GeminiContent(val parts: List<GeminiPart> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class GeminiPart(val text: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ParsedQueryWire(
	val keyword: String? = null,
	@JsonProperty("category_hint") val categoryHint: String? = null,
	@JsonProperty("geo_anchor") val geoAnchor: String? = null,
	@JsonProperty("radius_m") val radiusM: Int? = null,
	@JsonProperty("expects_empty") val expectsEmpty: Boolean = false,
)
