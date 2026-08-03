package dev.yubin.search.web

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.ErrorResponse
import dev.yubin.search.core.embed.EmbeddingModel
import dev.yubin.search.observability.QueryMetrics
import dev.yubin.search.query.PlaceSearchService
import dev.yubin.search.query.PlaceSuggestService
import dev.yubin.search.query.QueryLog
import dev.yubin.search.query.SearchController
import dev.yubin.search.query.SearchRequest
import dev.yubin.search.query.SearchResponse
import dev.yubin.search.vector.PlaceVectorSearchService
import dev.yubin.search.vector.QdrantSearchStore
import dev.yubin.search.vector.VectorSearchController
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException
import kotlin.test.Test

class SearchBackendErrorHandlerTest {
	@Test
	fun `an ES connection failure maps to 503 rather than 500`() {
		val client = clientThrowing(IOException("connection refused"))

		client.get().uri("/v1/search?q=test")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
			.expectBody()
			.jsonPath("$.backend").isEqualTo("elasticsearch")
	}

	@Test
	fun `a missing alias before indexing maps to 503`() {
		val notFound = ElasticsearchException(
			"search",
			ErrorResponse.of { r ->
				r.status(404).error { c -> c.type("index_not_found_exception").reason("no such index") }
			},
		)
		val client = clientThrowing(notFound)

		client.get().uri("/v1/search?q=test")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
			.expectBody()
			.jsonPath("$.backend").isEqualTo("elasticsearch")
	}

	@Test
	fun `an exception wrapping a backend exception follows the cause and maps to 503`() {
		val client = clientThrowing(IllegalStateException("inference failed", IOException("model file unreadable")))

		client.get().uri("/v1/search?q=test")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
			.expectBody()
			.jsonPath("$.backend").isEqualTo("elasticsearch")
	}

	@Test
	fun `ES 4xx caused by a bad query we sent is not hidden behind 503`() {
		val badRequest = ElasticsearchException(
			"search",
			ErrorResponse.of { r ->
				r.status(400).error { c -> c.type("parsing_exception").reason("malformed query") }
			},
		)
		val client = clientThrowing(badRequest)

		client.get().uri("/v1/search?q=test")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
	}

	@Test
	fun `a bug unrelated to the backend surfaces as 500 instead of hiding behind 503`() {
		val client = clientThrowing(IllegalStateException("a real bug like a null pointer"))

		client.get().uri("/v1/search?q=test")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
	}

	@Test
	fun `a Qdrant outage becomes a 503 named qdrant on the vector endpoint`() {
		val e = WebClientResponseException.create(503, "Service Unavailable", HttpHeaders.EMPTY, ByteArray(0), null)
		val controller = VectorSearchController(ThrowingVectorService(e))
		val client = WebTestClient.bindToController(controller)
			.controllerAdvice(SearchBackendErrorHandler())
			.build()

		client.get().uri("/v1/vsearch?q=test")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
			.expectBody()
			.jsonPath("$.backend").isEqualTo("qdrant")
	}

	private fun clientThrowing(exception: Throwable): WebTestClient {
		val controller = SearchController(
			searchService = ThrowingSearchService(exception),
			suggestService = PlaceSuggestService(
				Mockito.mock(ElasticsearchAsyncClient::class.java),
				QueryMetrics(SimpleMeterRegistry()),
				QueryLog(),
				"test",
			),
		)
		return WebTestClient.bindToController(controller)
			.controllerAdvice(SearchBackendErrorHandler())
			.build()
	}

	private class ThrowingSearchService(private val exception: Throwable) : PlaceSearchService(
		Mockito.mock(ElasticsearchAsyncClient::class.java),
		QueryMetrics(SimpleMeterRegistry()),
		QueryLog(),
		"test",
	) {
		override suspend fun search(req: SearchRequest): SearchResponse = throw exception
	}

	private class ThrowingVectorService(private val exception: Throwable) : PlaceVectorSearchService(
		embeddingModel(),
		Mockito.mock(QdrantSearchStore::class.java),
		QueryMetrics(SimpleMeterRegistry()),
		QueryLog(),
		"test",
		0f,
		1,
	) {
		override suspend fun search(req: SearchRequest): SearchResponse = throw exception
	}

	companion object {
		private fun embeddingModel(): EmbeddingModel {
			val mock = Mockito.mock(EmbeddingModel::class.java)
			Mockito.`when`(mock.poolSize).thenReturn(1)
			return mock
		}
	}
}
