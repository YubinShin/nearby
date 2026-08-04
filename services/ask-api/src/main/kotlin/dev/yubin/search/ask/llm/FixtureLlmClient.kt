package dev.yubin.search.ask.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import dev.yubin.search.ask.ParsedQuery
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.text.Normalizer

@Component
@ConditionalOnProperty(name = ["psp.ask.llm"], havingValue = "fixture")
class FixtureLlmClient(
	@Value("\${psp.ask.fixtures.location}") private val location: String,
	private val prompt: AskPromptSpec,
	private val mapper: ObjectMapper,
	private val resources: ResourceLoader,
) : LlmClient {
	override val vendor = "fixture"

	private val index: FixtureIndex by lazy { loadIndex() }

	override suspend fun parse(query: String): ParsedQuery {
		val key = normalize(query)
		val entry = index.entries[key]
			?: throw LlmException(
				"no fixture recorded for '$key'. run scripts/record_llm_fixtures.py to record it, " +
					"or query one of: ${index.entries.keys.joinToString(", ")}",
			)
		val raw = read(resource(entry.file))
		return GeminiWire.decode(mapper.readValue(raw, GeminiResponse::class.java), mapper)
	}

	private fun loadIndex(): FixtureIndex {
		val resource = resource(INDEX_FILE)
		if (!resource.exists()) {
			throw IllegalStateException(
				"fixture index ${resource.description} is missing. run scripts/record_llm_fixtures.py first, " +
					"or point psp.ask.fixtures.location at the directory that holds it.",
			)
		}
		val loaded = mapper.readValue(read(resource), FixtureIndex::class.java)
		if (loaded.promptVersion != prompt.version) {
			log.warn(
				"fixtures were recorded with prompt version {} but this build carries {} — re-record them",
				loaded.promptVersion,
				prompt.version,
			)
		}
		return loaded.copy(entries = loaded.entries.mapKeys { normalize(it.key) })
	}

	private fun resource(name: String): Resource =
		resources.getResource(location.removeSuffix("/") + "/" + name)

	private fun read(resource: Resource) =
		resource.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

	private fun normalize(query: String) = Normalizer.normalize(query.trim(), Normalizer.Form.NFC)

	private companion object {
		const val INDEX_FILE = "index.json"

		val log = LoggerFactory.getLogger(FixtureLlmClient::class.java)
	}
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class FixtureIndex(
	val promptVersion: String? = null,
	val model: String? = null,
	val entries: Map<String, FixtureEntry> = emptyMap(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FixtureEntry(val file: String, val source: String? = null)