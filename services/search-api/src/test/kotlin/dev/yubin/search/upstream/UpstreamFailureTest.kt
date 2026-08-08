package dev.yubin.search.upstream

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.ErrorResponse
import co.elastic.clients.json.JsonpMappingException
import io.netty.handler.timeout.ReadTimeoutException
import jakarta.json.stream.JsonParsingException
import org.springframework.core.codec.DecodingException
import org.springframework.core.codec.EncodingException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UpstreamFailureTest {
	@Test
	fun `ES IO exception counts as an upstream failure`() {
		assertNotNull(UpstreamFailure.of(IOException("connection refused")))
	}

	@Test
	fun `UncheckedIOException wrapping an ES IO exception counts as an upstream failure`() {
		assertNotNull(UpstreamFailure.of(UncheckedIOException(IOException("connection refused"))))
	}

	@Test
	fun `another exception type wrapping an upstream exception follows the cause and counts as an upstream failure`() {
		val wrapped = IllegalStateException("inference failed", IOException("model file unreadable"))

		assertNotNull(UpstreamFailure.of(wrapped))
	}

	@Test
	fun `a cyclic cause chain does not loop forever`() {
		val outer = RuntimeException("outer")
		val inner = RuntimeException("inner", outer)
		outer.initCause(inner)

		assertNull(UpstreamFailure.of(outer))
	}

	@Test
	fun `Qdrant WebClient exception counts as an upstream failure`() {
		val e = WebClientResponseException.create(503, "Service Unavailable", HttpHeaders.EMPTY, ByteArray(0), null)

		assertNotNull(UpstreamFailure.of(e))
	}

	@Test
	fun `Qdrant response decoding failure counts as an upstream failure`() {
		assertNotNull(UpstreamFailure.of(DecodingException("malformed body")))
	}

	@Test
	fun `an error while serializing our own response is not an upstream failure`() {
		assertNull(UpstreamFailure.of(EncodingException("cannot write PlaceHit")))
	}

	@Test
	fun `ES 5xx response counts as an upstream failure`() {
		val e = ElasticsearchException("search", errorResponse(status = 503))

		assertNotNull(UpstreamFailure.of(e))
	}

	@Test
	fun `ES 404 from a missing alias before indexing counts as an upstream failure`() {
		val e = ElasticsearchException("search", errorResponse(status = 404))

		assertNotNull(UpstreamFailure.of(e))
	}

	@Test
	fun `ES 429 thrown under overload counts as an upstream failure`() {
		val e = ElasticsearchException("search", errorResponse(status = 429))

		assertNotNull(UpstreamFailure.of(e))
	}

	@Test
	fun `Qdrant 404 from a missing collection counts as an upstream failure`() {
		val e = WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, ByteArray(0), null)

		assertNotNull(UpstreamFailure.of(e))
	}

	@Test
	fun `a Qdrant response timeout counts as an upstream failure so the channel degrades`() {
		val e = WebClientRequestException(
			ReadTimeoutException.INSTANCE,
			HttpMethod.POST,
			URI.create("http://localhost:6333/collections/place_vec/points/query"),
			HttpHeaders.EMPTY,
		)

		assertEquals(Upstream.QDRANT, UpstreamFailure.of(e))
	}

	@Test
	fun `Qdrant 429 counts as an upstream failure`() {
		val e = WebClientResponseException.create(429, "Too Many Requests", HttpHeaders.EMPTY, ByteArray(0), null)

		assertNotNull(UpstreamFailure.of(e))
	}

	@Test
	fun `ES response parsing failure counts as an upstream failure`() {
		assertNotNull(UpstreamFailure.of(JsonParsingException("malformed json", null)))
	}

	@Test
	fun `a mapping error from our own document schema mismatch is not an upstream failure`() {
		assertNull(UpstreamFailure.of(JsonpMappingException("location: geo_point shape differs", null)))
	}

	@Test
	fun `an exception unrelated to any upstream (a bug) is not an upstream failure`() {
		assertNull(UpstreamFailure.of(IllegalStateException("a real bug like a null pointer")))
	}

	@Test
	fun `ES 4xx caused by a bad query we sent is not an upstream failure`() {
		val e = ElasticsearchException("search", errorResponse(status = 400))

		assertNull(UpstreamFailure.of(e))
	}

	@Test
	fun `Qdrant 4xx caused by a bad request we sent is not an upstream failure`() {
		val e = WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY, ByteArray(0), null)

		assertNull(UpstreamFailure.of(e))
	}

	@Test
	fun `the upstream is decided by the exception type alone`() {
		val qdrant = WebClientResponseException.create(503, "Service Unavailable", HttpHeaders.EMPTY, ByteArray(0), null)
		val es = ElasticsearchException("search", errorResponse(status = 503))

		assertEquals(Upstream.QDRANT, UpstreamFailure.of(qdrant))
		assertEquals(Upstream.ELASTICSEARCH, UpstreamFailure.of(es))
		assertNull(UpstreamFailure.of(IllegalStateException("a real bug")))
	}

	private fun errorResponse(status: Int): ErrorResponse =
		ErrorResponse.of { r -> r.status(status).error { c -> c.type("test_error").reason("test") } }
}