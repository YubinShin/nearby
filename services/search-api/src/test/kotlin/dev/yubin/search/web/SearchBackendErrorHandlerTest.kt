package dev.yubin.search.web

import co.elastic.clients.elasticsearch.ElasticsearchClient
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
	fun `ES 연결 실패는 500이 아니라 503으로 매핑된다`() {
		val client = clientThrowing(IOException("connection refused"))

		client.get().uri("/v1/search?q=test")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
			.expectBody()
			.jsonPath("$.backend").isEqualTo("elasticsearch")
	}

	@Test
	fun `색인 전이라 별칭이 없으면 503으로 매핑된다`() {
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
	fun `백엔드 예외를 감싼 예외도 원인을 따라가 503으로 매핑된다`() {
		val client = clientThrowing(IllegalStateException("추론 실패", IOException("model file unreadable")))

		client.get().uri("/v1/search?q=test")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
			.expectBody()
			.jsonPath("$.backend").isEqualTo("elasticsearch")
	}

	@Test
	fun `우리가 보낸 잘못된 쿼리로 인한 ES 4xx는 503으로 감춰지지 않는다`() {
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
	fun `백엔드와 무관한 버그는 503으로 감춰지지 않고 500으로 드러난다`() {
		val client = clientThrowing(IllegalStateException("null pointer 같은 진짜 버그"))

		client.get().uri("/v1/search?q=test")
			.exchange()
			.expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
	}

	@Test
	fun `Qdrant 장애는 벡터 엔드포인트에서 qdrant 이름으로 503이 된다`() {
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
				Mockito.mock(ElasticsearchClient::class.java),
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
		Mockito.mock(ElasticsearchClient::class.java),
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
