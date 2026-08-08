package dev.yubin.search.ask.corpus

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.text.Normalizer

@Component
class UnsupportedFilters(
	@Value("\${psp.ask.corpus.lexicon}") location: String,
	resources: ResourceLoader,
	mapper: ObjectMapper,
) {
	private val attributes: List<CorpusAttribute>

	init {
		val resource = resources.getResource(location)
		if (!resource.exists()) {
			throw IllegalStateException("corpus lexicon ${resource.description} is missing")
		}
		val raw = resource.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
		val loaded = mapper.readValue(raw, CorpusLexicon::class.java).attributes
		if (loaded.isEmpty() || loaded.any { it.name.isBlank() || it.terms.isEmpty() }) {
			throw IllegalStateException("corpus lexicon ${resource.description} has an empty attribute or term list")
		}
		attributes = loaded.map { it.copy(terms = it.terms.map(::fold), exceptions = it.exceptions.map(::fold)) }
	}

	val names: List<String> get() = attributes.map { it.name }

	fun detect(query: String): List<String> = scan(query).map { it.name }

	fun strip(query: String): String {
		val covered = scan(query).flatMapTo(HashSet()) { it.positions }
		if (covered.isEmpty()) return query
		val normalized = Normalizer.normalize(query, Normalizer.Form.NFC)
		val kept = TOKEN.findAll(normalized)
			.filterNot { token -> token.range.any { it in covered } }
			.map { it.value }
			.toList()
		return if (kept.isEmpty()) query else kept.joinToString(" ")
	}

	private fun scan(query: String): List<Match> {
		val normalized = Normalizer.normalize(query, Normalizer.Form.NFC)
		val origin = ArrayList<Int>(normalized.length)
		val folded = StringBuilder(normalized.length)
		normalized.forEachIndexed { index, char ->
			if (!char.isWhitespace()) {
				folded.append(char)
				origin.add(index)
			}
		}
		if (folded.isEmpty()) return emptyList()

		val text = folded.toString()
		return attributes.mapNotNull { attribute ->
			val blocked = attribute.exceptions.flatMap { occurrences(text, it) }.flatMapTo(HashSet()) { it }
			val positions = attribute.terms
				.flatMap { occurrences(text, it) }
				.filterNot { occurrence -> occurrence.any { it in blocked } }
				.flatMapTo(HashSet()) { occurrence -> occurrence.map { origin[it] } }
			if (positions.isEmpty()) null else Match(attribute.name, positions)
		}
	}

	private class Match(val name: String, val positions: Set<Int>)

	private companion object {
		val TOKEN = Regex("\\S+")

		fun occurrences(text: String, needle: String): List<IntRange> {
			if (needle.isEmpty()) return emptyList()
			val found = ArrayList<IntRange>()
			var from = text.indexOf(needle)
			while (from >= 0) {
				found.add(from until from + needle.length)
				from = text.indexOf(needle, from + 1)
			}
			return found
		}

		fun fold(text: String) =
			Normalizer.normalize(text, Normalizer.Form.NFC).filterNot { it.isWhitespace() }
	}
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class CorpusLexicon(val attributes: List<CorpusAttribute> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class CorpusAttribute(
	val name: String = "",
	val terms: List<String> = emptyList(),
	val exceptions: List<String> = emptyList(),
)
