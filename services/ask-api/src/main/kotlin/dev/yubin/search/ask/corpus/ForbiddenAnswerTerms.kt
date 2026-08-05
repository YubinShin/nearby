package dev.yubin.search.ask.corpus

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.text.Normalizer

@Component
class ForbiddenAnswerTerms(
	@Value("\${psp.ask.corpus.forbidden-answer-terms}") location: String,
	resources: ResourceLoader,
	mapper: ObjectMapper,
) {
	private val attributes: List<CorpusAttribute>

	init {
		val resource = resources.getResource(location)
		if (!resource.exists()) {
			throw IllegalStateException("forbidden answer terms ${resource.description} is missing")
		}
		val raw = resource.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
		val loaded = mapper.readValue(raw, CorpusLexicon::class.java).attributes
		if (loaded.isEmpty() || loaded.any { it.name.isBlank() || it.terms.isEmpty() }) {
			throw IllegalStateException("forbidden answer terms ${resource.description} has an empty attribute or term list")
		}
		attributes = loaded.map { it.copy(terms = it.terms.map(::fold), exceptions = it.exceptions.map(::fold)) }
	}

	val names: List<String> get() = attributes.map { it.name }

	fun detect(text: String, records: Collection<String> = emptyList()): List<String> {
		val folded = records.fold(fold(text)) { acc, record -> acc.replace(fold(record), "") }
		if (folded.isEmpty()) return emptyList()
		return attributes
			.filter { attribute ->
				val scanned = attribute.exceptions.fold(folded) { acc, exception -> acc.replace(exception, "") }
				attribute.terms.any { it in scanned }
			}
			.map { it.name }
	}

	private companion object {
		fun fold(text: String) =
			Normalizer.normalize(text, Normalizer.Form.NFC).filterNot { it.isWhitespace() }
	}
}
