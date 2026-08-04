package dev.yubin.search.ask.llm

import dev.yubin.search.ask.ParsedQuery

interface LlmClient {
	val vendor: String

	suspend fun parse(query: String): ParsedQuery
}

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
