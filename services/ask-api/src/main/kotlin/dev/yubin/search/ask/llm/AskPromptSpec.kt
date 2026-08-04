package dev.yubin.search.ask.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class AskPromptSpec(mapper: ObjectMapper) {
	private val spec: PromptSpec = ClassPathResource(RESOURCE).inputStream
		.use { mapper.readValue(it.readBytes().toString(Charsets.UTF_8), PromptSpec::class.java) }

	val version: String get() = spec.version
	val system: String get() = spec.system
	val responseSchema: Map<String, Any?> get() = spec.responseSchema

	fun request(query: String): Map<String, Any?> = mapOf(
		"systemInstruction" to mapOf("parts" to listOf(mapOf("text" to system))),
		"contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to query)))),
		"generationConfig" to mapOf(
			"temperature" to 0,
			"responseMimeType" to "application/json",
			"responseSchema" to responseSchema,
		),
	)

	companion object {
		const val RESOURCE = "prompt/ask-parse.json"
	}
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PromptSpec(
	val version: String,
	val system: String,
	val responseSchema: Map<String, Any?>,
)
