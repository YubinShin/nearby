package dev.yubin.search.ask

import dev.yubin.search.ask.corpus.UnsupportedFilters
import dev.yubin.search.ask.llm.LlmClient
import dev.yubin.search.ask.llm.LlmFailures
import dev.yubin.search.ask.observability.AskMetrics
import dev.yubin.search.ask.search.SearchPlatform
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AskService(
	private val llm: LlmClient,
	private val unsupportedFilters: UnsupportedFilters,
	private val search: SearchPlatform,
	private val metrics: AskMetrics,
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
			unsupported = unsupportedFilters.detect(raw),
		)

		val searchStartedAt = System.nanoTime()
		val result = metrics.record(SEARCH) { search.hsearch(plan) }
		val searchTookMs = elapsedMs(searchStartedAt)

		val degradedBy = buildList {
			if (raw.isNotBlank() && parsed == null) add(LLM)
			if (result.degraded) {
				add(SEARCH)
				metrics.degraded(SEARCH, "channel")
			}
		}

		return AskResponse(
			query = raw,
			parsed = parsed,
			applied = plan,
			degraded = degradedBy.isNotEmpty(),
			degradedBy = degradedBy,
			llmVendor = llm.vendor,
			llmTookMs = llmTookMs,
			searchTookMs = searchTookMs,
			tookMs = elapsedMs(startedAt),
			search = result.body,
		)
	}

	private suspend fun parse(raw: String): ParsedQuery? = try {
		metrics.record(LLM) { llm.parse(raw) }
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		val reason = LlmFailures.reasonOf(e)
		metrics.degraded(LLM, reason)
		if (reason == LlmFailures.CONFIG) {
			log.error("{} rejected the credentials — every query runs without understanding until this is fixed", llm.vendor, e)
		} else {
			log.warn("{} parse failed, falling back to the raw query — {}: {}", llm.vendor, e.javaClass.simpleName, e.message)
			log.debug("llm parse failure detail", e)
		}
		null
	}

	private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

	private companion object {
		const val LLM = "llm"
		const val SEARCH = "search"

		val log = LoggerFactory.getLogger(AskService::class.java)
	}
}
