package dev.yubin.search.web

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchBackendErrorHandlerTest {
	private val handler = SearchBackendErrorHandler()

	@Test
	fun `ES 연결 실패는 500이 아니라 503으로 매핑된다`() {
		val resp = handler.onElasticsearchUnavailable(IOException("connection refused"))

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.statusCode)
		assertEquals("elasticsearch", resp.body?.backend)
	}

	@Test
	fun `Qdrant 연결 실패도 503으로 매핑된다`() {
		val e = WebClientResponseException.create(503, "Service Unavailable", HttpHeaders.EMPTY, ByteArray(0), null)

		val resp = handler.onQdrantUnavailable(e)

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.statusCode)
		assertEquals("qdrant", resp.body?.backend)
	}
}
