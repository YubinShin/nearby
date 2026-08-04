package dev.yubin.search.observability

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("infra")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class PrometheusScrapeTest @Autowired constructor(
	private val client: WebTestClient,
	private val metrics: QueryMetrics,
) {
	@Test
	fun `the exposed prometheus endpoint actually serves the registry`() {
		runBlocking { metrics.record("keyword") {} }
		metrics.stage("vector", "embed") {}

		val body = scrape()

		assertTrue("""psp_query_latency_seconds_count{application="search-api",channel="keyword",outcome="success"}""" in body)
		assertTrue("""psp_query_stage_latency_seconds_count{application="search-api",channel="vector",stage="embed"}""" in body)
	}

	@Test
	fun `the configured histogram reaches the scrape so a p95 can be computed`() {
		runBlocking { metrics.record("keyword") {} }

		val body = scrape()

		assertTrue("# TYPE psp_query_latency_seconds histogram" in body, body.take(200))
		assertTrue("psp_query_latency_seconds_bucket" in body)
		assertTrue("""le="0.01"""" in body, "the 10ms SLO boundary is missing")
	}

	private fun scrape(): String {
		val body = client.get().uri("/actuator/prometheus")
			.exchange()
			.expectStatus().isOk
			.expectBody(String::class.java)
			.returnResult()
			.responseBody
		assertNotNull(body)
		return body
	}
}
