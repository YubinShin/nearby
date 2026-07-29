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
	fun `ES IO exception counts as a backend failure`() {
		assertTrue(BackendFailure.causedBy(IOException("connection refused")))
	}

	@Test
	fun `UncheckedIOException wrapping an ES IO exception counts as a backend failure`() {
		assertTrue(BackendFailure.causedBy(UncheckedIOException(IOException("connection refused"))))
	}

	@Test
	fun `another exception type wrapping a backend exception follows the cause and counts as a backend failure`() {
		val wrapped = IllegalStateException("inference failed", IOException("model file unreadable"))

		assertTrue(BackendFailure.causedBy(wrapped))
	}

	@Test
	fun `a cyclic cause chain does not loop forever`() {
		val outer = RuntimeException("outer")
		val inner = RuntimeException("inner", outer)
		outer.initCause(inner)

		assertFalse(BackendFailure.causedBy(outer))
	}

	@Test
	fun `Qdrant WebClient exception counts as a backend failure`() {
		val e = WebClientResponseException.create(503, "Service Unavailable", HttpHeaders.EMPTY, ByteArray(0), null)

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `Qdrant response decoding failure counts as a backend failure`() {
		assertTrue(BackendFailure.causedBy(DecodingException("malformed body")))
	}

	@Test
	fun `an error while serializing our own response is not a backend failure`() {
		assertFalse(BackendFailure.causedBy(EncodingException("cannot write PlaceHit")))
	}

	@Test
	fun `ES 5xx response counts as a backend failure`() {
		val e = ElasticsearchException("search", errorResponse(status = 503))

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `ES 404 from a missing alias before indexing counts as a backend failure`() {
		val e = ElasticsearchException("search", errorResponse(status = 404))

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `ES 429 thrown under overload counts as a backend failure`() {
		val e = ElasticsearchException("search", errorResponse(status = 429))

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `Qdrant 404 from a missing collection counts as a backend failure`() {
		val e = WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, ByteArray(0), null)

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `Qdrant 429 counts as a backend failure`() {
		val e = WebClientResponseException.create(429, "Too Many Requests", HttpHeaders.EMPTY, ByteArray(0), null)

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `ES response parsing failure counts as a backend failure`() {
		assertTrue(BackendFailure.causedBy(JsonParsingException("malformed json", null)))
	}

	@Test
	fun `a mapping error from our own document schema mismatch is not a backend failure`() {
		assertFalse(BackendFailure.causedBy(JsonpMappingException("location: geo_point shape differs", null)))
	}

	@Test
	fun `an exception unrelated to the backend (a bug) is not a backend failure`() {
		assertFalse(BackendFailure.causedBy(IllegalStateException("a real bug like a null pointer")))
	}

	@Test
	fun `ES 4xx caused by a bad query we sent is not a backend failure`() {
		val e = ElasticsearchException("search", errorResponse(status = 400))

		assertFalse(BackendFailure.causedBy(e))
	}

	@Test
	fun `Qdrant 4xx caused by a bad request we sent is not a backend failure`() {
		val e = WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY, ByteArray(0), null)

		assertFalse(BackendFailure.causedBy(e))
	}

	@Test
	fun `the backend name is decided by the exception type alone`() {
		val qdrant = WebClientResponseException.create(503, "Service Unavailable", HttpHeaders.EMPTY, ByteArray(0), null)
		val es = ElasticsearchException("search", errorResponse(status = 503))

		assertEquals(BackendFailure.QDRANT, BackendFailure.backendOf(qdrant))
		assertEquals(BackendFailure.ELASTICSEARCH, BackendFailure.backendOf(es))
		assertNull(BackendFailure.backendOf(IllegalStateException("a real bug")))
	}

	private fun errorResponse(status: Int): ErrorResponse =
		ErrorResponse.of { r -> r.status(status).error { c -> c.type("test_error").reason("test") } }
}
