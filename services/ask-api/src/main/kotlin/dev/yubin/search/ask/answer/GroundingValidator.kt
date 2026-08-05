package dev.yubin.search.ask.answer

import dev.yubin.search.ask.Answer
import dev.yubin.search.ask.corpus.ForbiddenAnswerTerms
import dev.yubin.search.ask.search.PlaceRecord
import org.springframework.stereotype.Component

@Component
class GroundingValidator(private val forbidden: ForbiddenAnswerTerms) {
	fun validate(answer: Answer, records: List<PlaceRecord>): Answer {
		val names = records.associate { it.placeId to it.name }
		val dropped = sortedSetOf<String>()
		val drifting = sortedSetOf<String>()

		val sentences = answer.sentences.map { sentence ->
			val kept = sentence.evidence.filter { placeId ->
				val known = placeId in names
				if (!known) dropped += placeId
				known
			}
			kept.filterTo(drifting) { names.getValue(it) !in sentence.text }
			sentence.copy(evidence = kept)
		}

		return answer.copy(
			sentences = sentences,
			droppedEvidence = dropped.toList(),
			driftingEvidence = drifting.toList(),
			leakedTerms = forbidden.detect(sentences.joinToString(" ") { it.text }, names.values),
		)
	}
}
