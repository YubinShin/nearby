package dev.yubin.search.ask

import dev.yubin.search.ask.search.SearchPlatform
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(properties = ["psp.ask.llm=fixture"], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(AskControllerTest.StubSearchPlatformConfig::class)
class AskControllerTest @Autowired constructor(private val client: WebTestClient) {
	@Test
	fun `a request without q is rejected instead of answering an empty result set`() {
		client.get().uri("/v1/ask?size=20")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
	}

	@Test
	fun `a blank q is rejected too`() {
		client.get().uri("/v1/ask?q={q}", "  ")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
	}

	@Test
	fun `a query the fixtures carry is answered`() {
		client.get().uri("/v1/ask?q=카페")
			.exchange()
			.expectStatus().isOk
			.expectBody()
			.jsonPath("$.applied.q").isEqualTo("카페")
			.jsonPath("$.degraded").isEqualTo(false)
	}

	@Test
	fun `the per-stage latency is scrapable rather than living only in the response body`() {
		client.get().uri("/v1/ask?q={q}", "카페").exchange().expectStatus().isOk

		val body = client.get().uri("/actuator/prometheus")
			.exchange()
			.expectStatus().isOk
			.expectBody(String::class.java)
			.returnResult()
			.responseBody

		assertNotNull(body)
		assertTrue("psp_ask_latency" in body, body.take(400))
		assertTrue("""stage="llm"""" in body, body.take(400))
	}

	@TestConfiguration
	class StubSearchPlatformConfig {
		@Bean
		@Primary
		fun stubSearchPlatform(mapper: ObjectMapper): SearchPlatform = RecordingSearchPlatform(mapper)
	}
}
