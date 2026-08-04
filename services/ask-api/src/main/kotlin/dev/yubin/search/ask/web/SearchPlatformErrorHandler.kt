package dev.yubin.search.ask.web

import org.slf4j.LoggerFactory
import org.springframework.core.codec.CodecException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.reactive.function.client.WebClientException
import java.io.IOException

@RestControllerAdvice
class SearchPlatformErrorHandler {
	@ExceptionHandler(WebClientException::class, CodecException::class, IOException::class)
	fun onSearchPlatformFailure(e: Exception): ResponseEntity<UpstreamErrorResponse> {
		log.warn("search-api upstream call failed — down, or not reachable from ask-api", e)
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(
				UpstreamErrorResponse(
					upstream = UPSTREAM,
					message = "cannot reach the search-api upstream — not started yet, or temporarily down",
				),
			)
	}

	private companion object {
		const val UPSTREAM = "search-api"

		val log = LoggerFactory.getLogger(SearchPlatformErrorHandler::class.java)
	}
}

data class UpstreamErrorResponse(val upstream: String, val message: String)
