package dev.yubin.search.ask.answer

import dev.yubin.search.ask.Answer
import dev.yubin.search.ask.llm.LlmClient
import dev.yubin.search.ask.llm.LlmFailures
import dev.yubin.search.ask.observability.AskMetrics
import dev.yubin.search.ask.search.SearchResult
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AnswerService(
	private val llm: LlmClient,
	private val context: AnswerContext,
	private val validator: GroundingValidator,
	private val metrics: AskMetrics,
) {
	suspend fun answer(question: String, result: SearchResult): Answer? = try {
		val generated = metrics.record(ANSWER) { llm.answer(question, context.render(result.records)) }
		validator.validate(generated, result.records).copy(unrenderableRecords = result.unrenderable)
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		val reason = LlmFailures.reasonOf(e)
		metrics.degraded(ANSWER, reason)
		if (reason == LlmFailures.CONFIG) {
			log.error("{} rejected the credentials — every answer request degrades until this is fixed", llm.vendor, e)
		} else {
			log.warn("{} answer generation failed, returning the search result only — {}: {}", llm.vendor, e.javaClass.simpleName, e.message)
			log.debug("llm answer failure detail", e)
		}
		null
	}

	private companion object {
		const val ANSWER = "answer"

		val log = LoggerFactory.getLogger(AnswerService::class.java)
	}
}
