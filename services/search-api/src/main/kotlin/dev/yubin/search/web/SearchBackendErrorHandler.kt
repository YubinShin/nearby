package dev.yubin.search.web

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import dev.yubin.search.backend.BackendFailure
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
class SearchBackendErrorHandler {
	@ExceptionHandler(
		ElasticsearchException::class,
		IOException::class,
		UncheckedIOException::class,
		WebClientException::class,
		CodecException::class,
		JsonException::class,
	)
	fun onBackendException(e: Exception): ResponseEntity<BackendErrorResponse> {
		val backend = BackendFailure.backendOf(e) ?: throw e
		log.warn("{} 백엔드 호출 실패 — 아직 색인 전이거나 장애 상태일 수 있다", backend, e)
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(BackendErrorResponse(backend = backend, message = "$backend 백엔드에 연결할 수 없습니다 — 아직 색인 전이거나 일시 장애일 수 있습니다"))
	}

	companion object {
		private val log = LoggerFactory.getLogger(SearchBackendErrorHandler::class.java)
	}
}

data class BackendErrorResponse(val backend: String, val message: String)
