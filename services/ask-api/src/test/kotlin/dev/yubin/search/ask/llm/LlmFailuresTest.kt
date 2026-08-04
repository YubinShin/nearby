package dev.yubin.search.ask.llm

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException
import kotlin.test.assertEquals

class LlmFailuresTest {
	@Test
	fun `a revoked key is a config failure rather than a transient outage`() {
		assertEquals(LlmFailures.CONFIG, LlmFailures.reasonOf(status(401)))
		assertEquals(LlmFailures.CONFIG, LlmFailures.reasonOf(status(403)))
	}

	@Test
	fun `the free tier limit is its own reason`() {
		assertEquals("rate_limit", LlmFailures.reasonOf(status(429)))
	}

	@Test
	fun `a vendor outage and a bad request are told apart`() {
		assertEquals("upstream", LlmFailures.reasonOf(status(503)))
		assertEquals("request", LlmFailures.reasonOf(status(400)))
	}

	@Test
	fun `a payload that does not match the schema is not blamed on the network`() {
		assertEquals("payload", LlmFailures.reasonOf(LlmException("no candidate")))
		assertEquals("unreachable", LlmFailures.reasonOf(IOException("connection refused")))
	}

	private fun status(code: Int) =
		WebClientResponseException.create(code, "", HttpHeaders.EMPTY, ByteArray(0), null)
}
