package dev.yubin.search.hybrid

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import dev.yubin.search.core.embed.EmbeddingModel
import dev.yubin.search.observability.QueryMetrics
import dev.yubin.search.query.PlaceHit
import dev.yubin.search.query.PlaceSearchService
import dev.yubin.search.query.QueryLog
import dev.yubin.search.query.SearchRequest
import dev.yubin.search.query.SearchResponse
import dev.yubin.search.vector.EmbedGate
import dev.yubin.search.vector.EmbedOverloadException
import dev.yubin.search.vector.PlaceVectorSearchService
import dev.yubin.search.vector.QdrantSearchStore
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HybridSearchServiceTest {
	@Test
	fun `both channels succeeding returns the fused result without degrading`() = runBlocking {
		val service = hybridService(
			keyword = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("A"))) },
			vector = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("B"))) },
		)

		val resp = service.search(SearchRequest(q = "test"))

		assertFalse(resp.degraded)
		assertTrue(resp.channels.none { it.failed })
		assertEquals(setOf("A", "B"), resp.hits.map { it.place.placeId }.toSet())
	}

	@Test
	fun `a vector hit the keyword index no longer holds is dropped, not revived from the payload`() = runBlocking {
		val service = hybridService(
			keyword = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("A"))) },
			vector = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("B"))) },
			byIds = { emptyMap() },
		)

		val resp = service.search(SearchRequest(q = "test"))

		assertEquals(listOf("A"), resp.hits.map { it.place.placeId })
		assertFalse(resp.degraded, "the index answered — a document it does not hold is simply gone")
	}

	@Test
	fun `a failed hydrate keeps the payload but says the answer is degraded`() = runBlocking {
		val service = hybridService(
			keyword = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("A"))) },
			vector = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("B"))) },
			byIds = { throw IOException("connection refused") },
		)

		val resp = service.search(SearchRequest(q = "test"))

		assertEquals(setOf("A", "B"), resp.hits.map { it.place.placeId }.toSet())
		assertTrue(resp.degraded, "the index never answered, so the payload fallback is not verified")
		assertTrue(resp.channels.none { it.failed }, "neither channel failed — only the hydrate lookup did")
	}

	@Test
	fun `hits missing from the keyword channel are hydrated through byIds`() = runBlocking {
		val lookups = mutableListOf<List<String>>()
		val service = hybridService(
			keyword = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("A"))) },
			vector = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("B"))) },
			byIds = { ids ->
				lookups += ids
				ids.associateWith { hit(it, address = "서울 어딘가") }
			},
		)

		val resp = service.search(SearchRequest(q = "test"))

		assertEquals(listOf(listOf("B")), lookups)
		assertEquals("서울 어딘가", resp.hits.single { it.place.placeId == "B" }.place.address)
	}

	@Test
	fun `one channel failing on a backend outage still returns a degraded response from the other`() = runBlocking {
		val service = hybridService(
			keyword = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("A"))) },
			vector = { throw IOException("connection refused") },
		)

		val resp = service.search(SearchRequest(q = "test"))

		assertTrue(resp.degraded)
		assertTrue(resp.channels.single { it.name == HybridSearchService.VECTOR }.failed)
		assertEquals(listOf("A"), resp.hits.map { it.place.placeId })
	}

	@Test
	fun `a dead keyword channel skips hydration through the same ES and returns vector hits only`() = runBlocking {
		val lookups = mutableListOf<List<String>>()
		val service = hybridService(
			keyword = { throw IOException("connection refused") },
			vector = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("B"))) },
			byIds = { ids ->
				lookups += ids
				emptyMap()
			},
		)

		val resp = service.search(SearchRequest(q = "test"))

		assertTrue(resp.degraded)
		assertTrue(resp.channels.single { it.name == HybridSearchService.KEYWORD }.failed)
		assertEquals(listOf("B"), resp.hits.map { it.place.placeId })
		assertTrue(lookups.isEmpty())
	}

	@Test
	fun `a vector channel rejected by the embedding gate degrades to the keyword channel`() = runBlocking {
		val service = hybridService(
			keyword = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("A"))) },
			vector = { throw EmbedOverloadException(EmbedGate.QUEUE_FULL, 33) },
		)

		val resp = service.search(SearchRequest(q = "test"))

		assertTrue(resp.degraded)
		assertTrue(resp.channels.single { it.name == HybridSearchService.VECTOR }.failed)
		assertEquals(listOf("A"), resp.hits.map { it.place.placeId })
	}

	@Test
	fun `a bug unrelated to the backend fails the whole request instead of hiding behind degradation`() {
		val service = hybridService(
			keyword = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("A"))) },
			vector = { throw IllegalStateException("a real bug like a null pointer") },
		)

		assertFailsWith<IllegalStateException> {
			runBlocking { service.search(SearchRequest(q = "test")) }
		}
	}

	@Test
	fun `request cancellation propagates instead of being recorded as a channel failure`() {
		val service = hybridService(
			keyword = { SearchResponse(it.q, 1, it.page, it.size, 0, hits = listOf(hit("A"))) },
			vector = { throw CancellationException("client disconnected") },
		)

		assertFailsWith<CancellationException> {
			runBlocking { service.search(SearchRequest(q = "test")) }
		}
	}

	private fun hit(id: String, score: Double = 1.0, address: String? = null) = PlaceHit(
		placeId = id, name = id, branch = null, category = null, address = address,
		sigungu = null, dong = null, lat = null, lon = null, score = score,
	)

	private fun hybridService(
		keyword: (SearchRequest) -> SearchResponse,
		vector: (SearchRequest) -> SearchResponse,
		byIds: (List<String>) -> Map<String, PlaceHit> = { ids -> ids.associateWith { hit(it) } },
	) = HybridSearchService(
		keyword = FakeKeywordService(keyword, byIds),
		vector = FakeVectorService(vector),
		metrics = QueryMetrics(SimpleMeterRegistry()),
		queryLog = QueryLog(),
		k = 60,
		candidates = 50,
		keywordWeight = 1.0,
		vectorWeight = 1.0,
	)

	private class FakeKeywordService(
		private val result: (SearchRequest) -> SearchResponse,
		private val lookup: (List<String>) -> Map<String, PlaceHit>,
	) : PlaceSearchService(
		Mockito.mock(ElasticsearchAsyncClient::class.java),
		QueryMetrics(SimpleMeterRegistry()),
		QueryLog(),
		"test",
	) {
		override suspend fun search(req: SearchRequest): SearchResponse = result(req)

		override suspend fun byIds(ids: List<String>): Map<String, PlaceHit> = lookup(ids)
	}

	private class FakeVectorService(private val result: (SearchRequest) -> SearchResponse) : PlaceVectorSearchService(
		embeddingModel(),
		embedGate(),
		Mockito.mock(QdrantSearchStore::class.java),
		QueryMetrics(SimpleMeterRegistry()),
		QueryLog(),
		"test",
		0f,
		1,
	) {
		override suspend fun search(req: SearchRequest): SearchResponse = result(req)
	}

	companion object {
		private fun embeddingModel(): EmbeddingModel {
			val mock = Mockito.mock(EmbeddingModel::class.java)
			Mockito.`when`(mock.poolSize).thenReturn(1)
			return mock
		}

		private fun embedGate() = EmbedGate(
			poolSize = 1,
			maxQueue = 8,
			waitTimeout = Duration.ofSeconds(1),
			metrics = QueryMetrics(SimpleMeterRegistry()),
		)
	}
}
