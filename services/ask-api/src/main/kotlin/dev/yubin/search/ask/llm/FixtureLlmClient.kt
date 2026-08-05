package dev.yubin.search.ask.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import dev.yubin.search.ask.Answer
import dev.yubin.search.ask.ParsedQuery
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
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

	private val index: FixtureIndex = loadIndex()

	private val answerIndex: FixtureIndex by lazy {
		val resource = resource("$ANSWER_DIR/$INDEX_FILE")
		if (!resource.exists()) {
			throw IllegalStateException(
				"answer fixture index ${resource.description} is missing. record it, " +
					"or point psp.ask.fixtures.location at the directory that holds it.",
			)
		}
		mapper.readValue(read(resource), FixtureIndex::class.java)
	}

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

	override suspend fun answer(question: String, context: String): Answer {
		val key = fingerprint(question, context)
		val entry = answerIndex.entries[key]
			?: throw LlmException(
				"no answer fixture recorded for '${normalize(question)}' with the rendered context (key=$key). " +
					"recorded keys: ${answerIndex.entries.keys.joinToString(", ")}",
			)
		val raw = read(resource("$ANSWER_DIR/${entry.file}"))
		return AnswerWire.decode(mapper.readValue(raw, GeminiResponse::class.java), mapper)
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
		val stale = loaded.entries.filterValues { it.promptVersion != prompt.version }.keys
		if (loaded.promptVersion != prompt.version || stale.isNotEmpty()) {
			log.warn(
				"fixtures do not match prompt version {} (index says {}) — re-record {}",
				prompt.version,
				loaded.promptVersion,
				stale.joinToString(", ").ifEmpty { "them" },
			)
		}
		return loaded.copy(entries = loaded.entries.mapKeys { normalize(it.key) })
	}

	private fun resource(name: String): Resource =
		resources.getResource(location.removeSuffix("/") + "/" + name)

	private fun read(resource: Resource) =
		resource.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

	private fun normalize(query: String) = Normalizer.normalize(query.trim(), Normalizer.Form.NFC)

	private fun fingerprint(question: String, context: String): String =
		MessageDigest.getInstance("SHA-256")
			.digest("${normalize(question)}$FINGERPRINT_SEPARATOR${normalize(context)}".toByteArray())
			.joinToString("") { "%02x".format(it) }
			.take(FINGERPRINT_LENGTH)

	private companion object {
		const val INDEX_FILE = "index.json"
		const val ANSWER_DIR = "answer"
		const val FINGERPRINT_SEPARATOR = "\n---\n"
		const val FINGERPRINT_LENGTH = 12

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
data class FixtureEntry(val file: String, val source: String? = null, val promptVersion: String? = null)