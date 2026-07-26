package dev.yubin.search.query

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class QueryLog {
	fun search(q: String, total: Long, relaxed: Boolean, tookMs: Long, channel: String = KEYWORD) {
		if (q.isBlank()) return
		log.info(
			"""{"type":"search","channel":"{}","q":"{}","total":{},"zero":{},"relaxed":{},"took_ms":{}}""",
			channel, escape(q), total, total == 0L, relaxed, tookMs,
		)
	}

	fun suggest(q: String, hits: Int, tookMs: Long) {
		if (q.isBlank()) return
		log.info(
			"""{"type":"suggest","q":"{}","hits":{},"zero":{},"took_ms":{}}""",
			escape(q), hits, hits == 0, tookMs,
		)
	}

	private fun escape(q: String): String =
		q.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

	companion object {
		const val KEYWORD = "keyword"

		private val log = LoggerFactory.getLogger("psp.querylog")
	}
}
