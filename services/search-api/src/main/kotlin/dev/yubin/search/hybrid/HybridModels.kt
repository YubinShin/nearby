package dev.yubin.search.hybrid

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonUnwrapped
import dev.yubin.search.debug.CapturedQuery
import dev.yubin.search.debug.Debuggable
import dev.yubin.search.query.PlaceHit

data class HybridHit(
	@get:JsonUnwrapped val place: PlaceHit,
	val ranks: Map<String, Int>,
	val scores: Map<String, Double>,
)

data class ChannelReport(
	val name: String,
	val candidates: Int,
	val tookMs: Long,
	val failed: Boolean = false,
)

data class HybridResponse(
	val query: String,
	val total: Long,
	val page: Int,
	val size: Int,
	val tookMs: Long,
	val degraded: Boolean = false,
	val channels: List<ChannelReport> = emptyList(),
	val hits: List<HybridHit> = emptyList(),
	@get:JsonInclude(JsonInclude.Include.NON_NULL)
	override val debug: List<CapturedQuery>? = null,
) : Debuggable<HybridResponse> {
	override fun withDebug(queries: List<CapturedQuery>) = copy(debug = queries)
}
