package dev.yubin.search.ask.llm

import org.springframework.web.reactive.function.client.WebClientResponseException

object LlmFailures {
	const val CONFIG = "config"

	fun reasonOf(e: Throwable): String = when {
		e !is WebClientResponseException -> if (e is LlmException) "payload" else "unreachable"
		e.statusCode.value() in CONFIG_STATUSES -> CONFIG
		e.statusCode.value() == TOO_MANY_REQUESTS -> "rate_limit"
		e.statusCode.value() >= SERVER_ERROR -> "upstream"
		else -> "request"
	}

	private const val TOO_MANY_REQUESTS = 429
	private const val SERVER_ERROR = 500

	private val CONFIG_STATUSES = setOf(401, 403)
}
