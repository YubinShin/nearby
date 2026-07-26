package dev.yubin.search.hybrid

import com.fasterxml.jackson.annotation.JsonUnwrapped
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
)
