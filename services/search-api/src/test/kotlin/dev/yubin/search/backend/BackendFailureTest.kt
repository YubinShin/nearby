package dev.yubin.search.backend

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.ErrorResponse
import co.elastic.clients.json.JsonpMappingException
import jakarta.json.stream.JsonParsingException
import org.springframework.core.codec.DecodingException
import org.springframework.core.codec.EncodingException
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException
import java.io.UncheckedIOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendFailureTest {
	@Test
	fun `ES IO 예외는 백엔드 실패로 판정한다`() {
		assertTrue(BackendFailure.causedBy(IOException("connection refused")))
	}

	@Test
	fun `ES IO 예외를 감싼 UncheckedIOException도 백엔드 실패로 판정한다`() {
		assertTrue(BackendFailure.causedBy(UncheckedIOException(IOException("connection refused"))))
	}

	@Test
	fun `백엔드 예외를 감싼 다른 타입의 예외도 원인을 따라가 백엔드 실패로 판정한다`() {
		val wrapped = IllegalStateException("추론 실패", IOException("model file unreadable"))

		assertTrue(BackendFailure.causedBy(wrapped))
	}

	@Test
	fun `원인 사슬이 순환해도 무한 루프에 빠지지 않는다`() {
		val outer = RuntimeException("outer")
		val inner = RuntimeException("inner", outer)
		outer.initCause(inner)

		assertFalse(BackendFailure.causedBy(outer))
	}

	@Test
	fun `Qdrant WebClient 예외는 백엔드 실패로 판정한다`() {
		val e = WebClientResponseException.create(503, "Service Unavailable", HttpHeaders.EMPTY, ByteArray(0), null)

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `Qdrant 응답 디코딩 실패도 백엔드 실패로 판정한다`() {
		assertTrue(BackendFailure.causedBy(DecodingException("malformed body")))
	}

	@Test
	fun `우리 응답을 직렬화하다 난 오류는 백엔드 실패가 아니다`() {
		assertFalse(BackendFailure.causedBy(EncodingException("cannot write PlaceHit")))
	}

	@Test
	fun `ES 5xx 응답은 백엔드 실패로 판정한다`() {
		val e = ElasticsearchException("search", errorResponse(status = 503))

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `색인 전이라 별칭이 없는 ES 404는 백엔드 실패로 판정한다`() {
		val e = ElasticsearchException("search", errorResponse(status = 404))

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `ES가 과부하로 던지는 429는 백엔드 실패로 판정한다`() {
		val e = ElasticsearchException("search", errorResponse(status = 429))

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `컬렉션이 없어서 나는 Qdrant 404도 백엔드 실패로 판정한다`() {
		val e = WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, ByteArray(0), null)

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `Qdrant 429도 백엔드 실패로 판정한다`() {
		val e = WebClientResponseException.create(429, "Too Many Requests", HttpHeaders.EMPTY, ByteArray(0), null)

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `ES 응답 파싱 실패도 백엔드 실패로 판정한다`() {
		assertTrue(BackendFailure.causedBy(JsonParsingException("malformed json", null)))
	}

	@Test
	fun `우리 문서 스키마와 어긋나서 나는 매핑 오류는 백엔드 실패가 아니다`() {
		assertFalse(BackendFailure.causedBy(JsonpMappingException("location: geo_point 모양이 다르다", null)))
	}

	@Test
	fun `백엔드와 무관한 예외(버그)는 실패로 판정하지 않는다`() {
		assertFalse(BackendFailure.causedBy(IllegalStateException("null pointer 같은 진짜 버그")))
	}

	@Test
	fun `우리가 보낸 잘못된 쿼리로 인한 ES 4xx는 실패로 판정하지 않는다`() {
		val e = ElasticsearchException("search", errorResponse(status = 400))

		assertFalse(BackendFailure.causedBy(e))
	}

	@Test
	fun `우리가 보낸 잘못된 요청으로 인한 Qdrant 4xx는 실패로 판정하지 않는다`() {
		val e = WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY, ByteArray(0), null)

		assertFalse(BackendFailure.causedBy(e))
	}

	@Test
	fun `백엔드 이름은 예외 종류 하나로 판정한다`() {
		val qdrant = WebClientResponseException.create(503, "Service Unavailable", HttpHeaders.EMPTY, ByteArray(0), null)
		val es = ElasticsearchException("search", errorResponse(status = 503))

		assertEquals(BackendFailure.QDRANT, BackendFailure.backendOf(qdrant))
		assertEquals(BackendFailure.ELASTICSEARCH, BackendFailure.backendOf(es))
		assertNull(BackendFailure.backendOf(IllegalStateException("진짜 버그")))
	}

	private fun errorResponse(status: Int): ErrorResponse =
		ErrorResponse.of { r -> r.status(status).error { c -> c.type("test_error").reason("test") } }
}
