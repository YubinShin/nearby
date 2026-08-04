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

	fun detect(query: String): List<String> {
		val folded = fold(query)
		if (folded.isEmpty()) return emptyList()
		return attributes
			.filter { attribute ->
				val text = attribute.exceptions.fold(folded) { acc, exception -> acc.replace(exception, "") }
				attribute.terms.any { it in text }
			}
			.map { it.name }
	}

	private companion object {
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
