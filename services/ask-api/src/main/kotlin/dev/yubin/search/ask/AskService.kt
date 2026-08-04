package dev.yubin.search.ask

import dev.yubin.search.ask.llm.LlmClient
import dev.yubin.search.ask.search.SearchPlatform
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AskService(
	private val llm: LlmClient,
	private val search: SearchPlatform,
	@Value("\${psp.ask.size}") private val defaultSize: Int,
) {
	suspend fun ask(q: String?, size: Int? = null, lat: Double? = null, lon: Double? = null): AskResponse {
		val startedAt = System.nanoTime()
		val raw = q?.trim().orEmpty()

		val llmStartedAt = System.nanoTime()
		val parsed = if (raw.isBlank()) null else parse(raw)
		val llmTookMs = if (raw.isBlank()) 0 else elapsedMs(llmStartedAt)

		val plan = AskQueryPlanner.plan(
			raw = raw,
			parsed = parsed,
			defaultSize = defaultSize,
			size = size,
			lat = lat,
			lon = lon,
		)

		val searchStartedAt = System.nanoTime()
		val result = search.hsearch(plan)
		val searchTookMs = elapsedMs(searchStartedAt)

		val degradedBy = buildList {
			if (raw.isNotBlank() && parsed == null) add(LLM)
			if (result.degraded) add(SEARCH)
		}

		return AskResponse(
			query = raw,
			parsed = parsed,
			applied = plan,
			degraded = degradedBy.isNotEmpty(),
			degradedBy = degradedBy,
			llmTookMs = llmTookMs,
			searchTookMs = searchTookMs,
			tookMs = elapsedMs(startedAt),
			search = result.body,
		)
	}

	private suspend fun parse(raw: String): ParsedQuery? = try {
		llm.parse(raw)
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		log.warn("{} parse failed, falling back to the raw query — {}: {}", llm.vendor, e.javaClass.simpleName, e.message)
		log.debug("llm parse failure detail", e)
		null
	}

	private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

	private companion object {
		const val LLM = "llm"
		const val SEARCH = "search"

		val log = LoggerFactory.getLogger(AskService::class.java)
	}
}
