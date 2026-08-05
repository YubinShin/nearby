package dev.yubin.search.ask.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import dev.yubin.search.ask.Answer
import dev.yubin.search.ask.AnswerSentence
import tools.jackson.databind.ObjectMapper
import java.text.Normalizer

object AnswerWire {
	fun decode(response: GeminiResponse, mapper: ObjectMapper): Answer {
		val candidate = response.candidates.firstOrNull()
			?: throw LlmException("gemini returned no candidate (promptFeedback=${response.promptFeedback})")
		val text = candidate.content?.parts?.firstNotNullOfOrNull { it.text?.ifBlank { null } }
			?: throw LlmException("gemini candidate carried no text (finishReason=${candidate.finishReason})")

		val decoded = try {
			mapper.readValue(text, AnswerWireBody::class.java)
		} catch (e: Exception) {
			throw LlmException("gemini returned a payload that does not match the answer schema: $text", e)
		}

		val sentences = decoded.sentences.mapNotNull { sentence ->
			sentence.text?.let(::normalize)?.ifBlank { null }?.let {
				AnswerSentence(text = it, evidence = sentence.evidence.map(::normalize).filter(String::isNotBlank))
			}
		}
		if (decoded.found && sentences.isEmpty()) {
			throw LlmException("gemini reported found=true with no sentence: $text")
		}

		return Answer(
			found = decoded.found,
			unverifiableConditions = decoded.unverifiableConditions.map(::normalize).filter(String::isNotBlank),
			sentences = sentences,
		)
	}

	private fun normalize(text: String) = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class AnswerWireBody(
	val found: Boolean = false,
	@JsonProperty("unverifiable_conditions") val unverifiableConditions: List<String> = emptyList(),
	val sentences: List<AnswerSentenceWire> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class AnswerSentenceWire(
	val text: String? = null,
	val evidence: List<String> = emptyList(),
)
