package dev.yubin.search.web

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.reactive.function.client.WebClientException
import java.io.IOException

@RestControllerAdvice
class SearchBackendErrorHandler {
	@ExceptionHandler(ElasticsearchException::class, IOException::class)
	fun onElasticsearchUnavailable(e: Exception): ResponseEntity<BackendErrorResponse> = unavailable("elasticsearch", e)

	@ExceptionHandler(WebClientException::class)
	fun onQdrantUnavailable(e: WebClientException): ResponseEntity<BackendErrorResponse> = unavailable("qdrant", e)

	private fun unavailable(backend: String, e: Exception): ResponseEntity<BackendErrorResponse> {
		log.warn("{} 백엔드 호출 실패 — 아직 색인 전이거나 장애 상태일 수 있다", backend, e)
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(BackendErrorResponse(backend = backend, message = "$backend 백엔드에 연결할 수 없습니다 — 아직 색인 전이거나 일시 장애일 수 있습니다"))
	}

	companion object {
		private val log = LoggerFactory.getLogger(SearchBackendErrorHandler::class.java)
	}
}

data class BackendErrorResponse(val backend: String, val message: String)
