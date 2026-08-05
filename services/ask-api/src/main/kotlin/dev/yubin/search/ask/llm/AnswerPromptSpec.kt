package dev.yubin.search.ask.llm

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class AnswerPromptSpec(mapper: ObjectMapper) {
	private val spec: PromptSpec = ClassPathResource(RESOURCE).inputStream
		.use { mapper.readValue(it.readBytes().toString(Charsets.UTF_8), PromptSpec::class.java) }

	val version: String get() = spec.version
	val system: String get() = spec.system
	val responseSchema: Map<String, Any?> get() = spec.responseSchema

	fun text(question: String, context: String) = "질문: $question\n\n$context\n\n$system"

	fun request(question: String, context: String): Map<String, Any?> = mapOf(
		"contents" to listOf(mapOf("parts" to listOf(mapOf("text" to text(question, context))))),
		"generationConfig" to mapOf(
			"thinkingConfig" to mapOf("thinkingLevel" to THINKING_LEVEL),
			"responseMimeType" to "application/json",
			"responseSchema" to responseSchema,
		),
	)

	companion object {
		const val RESOURCE = "prompt/answer-generate.json"
		const val THINKING_LEVEL = "minimal"
	}
}
