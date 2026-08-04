package dev.yubin.search.web

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import dev.yubin.search.upstream.UpstreamFailure
import jakarta.json.JsonException
import org.slf4j.LoggerFactory
import org.springframework.core.codec.CodecException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.reactive.function.client.WebClientException
import java.io.IOException
import java.io.UncheckedIOException

@RestControllerAdvice
class UpstreamErrorHandler {
	@ExceptionHandler(
		ElasticsearchException::class,
		IOException::class,
		UncheckedIOException::class,
		WebClientException::class,
		CodecException::class,
		JsonException::class,
	)
	fun onUpstreamException(e: Exception): ResponseEntity<UpstreamErrorResponse> {
		val upstream = UpstreamFailure.of(e) ?: throw e
		log.warn("{} upstream call failed — not indexed yet or currently down", upstream.wire, e)
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(UpstreamErrorResponse(upstream = upstream.wire, message = "cannot reach the ${upstream.wire} upstream — not indexed yet, or temporarily down"))
	}

	companion object {
		private val log = LoggerFactory.getLogger(UpstreamErrorHandler::class.java)
	}
}

data class UpstreamErrorResponse(val upstream: String, val message: String)
