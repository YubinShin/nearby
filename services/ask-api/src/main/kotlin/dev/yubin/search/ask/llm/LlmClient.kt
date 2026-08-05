package dev.yubin.search.ask.llm

import dev.yubin.search.ask.Answer
import dev.yubin.search.ask.ParsedQuery

interface LlmClient {
	val vendor: String

	suspend fun parse(query: String): ParsedQuery

	suspend fun answer(question: String, context: String): Answer
}

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
