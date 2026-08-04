package dev.yubin.search.ask.web

import dev.yubin.search.ask.AskController
import dev.yubin.search.ask.SearchRequestPlan
import dev.yubin.search.ask.search.SearchPlatform
import dev.yubin.search.ask.search.SearchResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
@Import(SearchPlatformErrorHandlerTest.FailingSearchPlatformConfig::class)
class SearchPlatformErrorHandlerTest @Autowired constructor(
	private val controller: AskController,
	private val platform: FailingSearchPlatform,
) {
	@Test
	fun `a 404 from a reachable search-api is not reported as an unreachable upstream`() {
		platform.failure = WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, ByteArray(0), null)

		client().get().uri("/v1/ask?q=카페")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
			.expectBody()
			.jsonPath("$.upstream").isEqualTo("search-api")
			.jsonPath("$.message").isEqualTo("search-api answered 404 — reachable, but the hsearch call was rejected")
	}

	@Test
	fun `a 400 from search-api keeps the blame on the call rather than on reachability`() {
		platform.failure = WebClientResponseException.create(400, "Bad Request", HttpHeaders.EMPTY, ByteArray(0), null)

		client().get().uri("/v1/ask?q=카페")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
	}

	@Test
	fun `a connection failure is still reported as an unreachable upstream`() {
		platform.failure = IOException("connection refused")

		client().get().uri("/v1/ask?q=카페")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
			.expectBody()
			.jsonPath("$.upstream").isEqualTo("search-api")
	}

	private fun client() = WebTestClient.bindToController(controller)
		.controllerAdvice(SearchPlatformErrorHandler())
		.build()

	@TestConfiguration
	class FailingSearchPlatformConfig {
		@Bean
		@Primary
		fun failingSearchPlatform() = FailingSearchPlatform()
	}
}

class FailingSearchPlatform : SearchPlatform {
	var failure: Exception = IOException("not set")

	override suspend fun hsearch(plan: SearchRequestPlan): SearchResult = throw failure
}
