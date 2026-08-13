package dev.yubin.search.web

import dev.yubin.search.vector.EmbedOverloadException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class OverloadErrorHandler {
	@ExceptionHandler(EmbedOverloadException::class)
	fun onEmbedOverload(e: EmbedOverloadException): ResponseEntity<OverloadErrorResponse> {
		log.warn("query embedding gate rejected a request — {} (queued {})", e.reason, e.queueDepth)
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
			.body(
				OverloadErrorResponse(
					reason = e.reason,
					message = "query embedding is saturated — retry shortly, or use /v1/search for the keyword channel",
				),
			)
	}

	companion object {
		private const val RETRY_AFTER_SECONDS = "1"

		private val log = LoggerFactory.getLogger(OverloadErrorHandler::class.java)
	}
}

data class OverloadErrorResponse(val reason: String, val message: String)
