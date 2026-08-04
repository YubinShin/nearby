package dev.yubin.search.ask.llm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
class GeminiClientTest @Autowired constructor(
	private val prompt: AskPromptSpec,
	private val mapper: ObjectMapper,
) {
	private val attempts = AtomicInteger()

	@Volatile
	private var failFor = 0

	private val server = HttpServer.create().port(0)
		.handle { _, response ->
			if (attempts.incrementAndGet() <= failFor) {
				response.status(429).send()
			} else {
				response.header("Content-Type", "application/json").sendString(Mono.just(ENVELOPE))
			}
		}
		.bindNow()

	@AfterEach
	fun stop() {
		server.disposeNow()
	}

	@Test
	fun `a rate-limited call is retried instead of degrading the request`() = runTest {
		failFor = 1

		val parsed = client().parse("카페")

		assertEquals("카페", parsed.keyword)
		assertEquals(2, attempts.get())
	}

	@Test
	fun `once the retries are used up the original 429 surfaces`() = runTest {
		failFor = Int.MAX_VALUE

		assertFailsWith<WebClientResponseException.TooManyRequests> { client().parse("카페") }
		assertEquals(3, attempts.get())
	}

	private fun client() =
		GeminiClient("http://127.0.0.1:${server.port()}", "test-model", "test-key", 5_000, prompt, mapper)

	private companion object {
		const val PAYLOAD = """{\"keyword\":\"카페\",\"expects_empty\":false}"""
		const val ENVELOPE = """{"candidates":[{"content":{"parts":[{"text":"$PAYLOAD"}]}}]}"""
	}
}
