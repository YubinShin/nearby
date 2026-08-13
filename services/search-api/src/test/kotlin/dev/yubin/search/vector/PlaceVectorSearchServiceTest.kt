package dev.yubin.search.vector

import dev.yubin.search.core.embed.EmbeddingModel
import dev.yubin.search.core.vector.VectorMatch
import dev.yubin.search.observability.QueryMetrics
import dev.yubin.search.query.QueryLog
import dev.yubin.search.query.SearchRequest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceVectorSearchServiceTest {
	@Test
	fun `without a filter the floor drops low scores`() = runBlocking {
		val store = FakeStore(narrowed = false)

		val resp = service(store).search(SearchRequest(q = "카페"))

		assertEquals(listOf("above"), resp.hits.map { it.placeId })
		assertEquals(0, store.narrowChecks)
	}

	@Test
	fun `a filter that removes nothing keeps the floor`() = runBlocking {
		val store = FakeStore(narrowed = false)

		val resp = service(store).search(SearchRequest(q = "카페", sigungu = "강남구"))

		assertEquals(listOf("above"), resp.hits.map { it.placeId })
		assertEquals(1, store.narrowChecks)
	}

	@Test
	fun `a filter that removes candidates drops the floor`() = runBlocking {
		val store = FakeStore(narrowed = true)

		val resp = service(store).search(SearchRequest(q = "카페", sigungu = "강남구"))

		assertEquals(listOf("above", "below"), resp.hits.map { it.placeId })
		assertEquals(1, store.narrowChecks)
	}

	@Test
	fun `equal scores are ordered by place id so the ranking survives a reindex`() = runBlocking {
		val shuffled = listOf(
			VectorMatch("c", 0.90f, mapOf("name" to "다")),
			VectorMatch("a", 0.90f, mapOf("name" to "가")),
			VectorMatch("b", 0.90f, mapOf("name" to "나")),
		)

		val resp = service(FakeStore(narrowed = false, matches = shuffled))
			.search(SearchRequest(q = "카페", size = 10))

		assertEquals(listOf("a", "b", "c"), resp.hits.map { it.placeId })
	}

	private fun service(store: FakeStore) = PlaceVectorSearchService(
		embeddings = embeddingModel(),
		gate = EmbedGate(
			poolSize = 1,
			maxQueue = 8,
			waitTimeout = Duration.ofSeconds(1),
			metrics = QueryMetrics(SimpleMeterRegistry()),
		),
		qdrant = store,
		metrics = QueryMetrics(SimpleMeterRegistry()),
		queryLog = QueryLog(),
		alias = "test",
		minScore = MIN_SCORE,
		cacheSize = 10,
	)

	private fun embeddingModel(): EmbeddingModel {
		val mock = Mockito.mock(EmbeddingModel::class.java)
		Mockito.`when`(mock.poolSize).thenReturn(1)
		Mockito.`when`(mock.embedQuery(Mockito.anyString())).thenReturn(FloatArray(4))
		return mock
	}

	private class FakeStore(
		private val narrowed: Boolean,
		private val matches: List<VectorMatch> = MATCHES,
	) : QdrantSearchStore("http://localhost", 1000) {
		var narrowChecks = 0

		override suspend fun query(
			collection: String,
			vector: FloatArray,
			limit: Int,
			filter: Map<String, Any?>?,
			efSearch: Int,
		): List<VectorMatch> = matches

		override suspend fun narrows(collection: String, filter: Map<String, Any?>): Boolean {
			narrowChecks++
			return narrowed
		}
	}

	private companion object {
		const val MIN_SCORE = 0.84f

		val MATCHES = listOf(
			VectorMatch("above", 0.90f, mapOf("name" to "위")),
			VectorMatch("below", 0.80f, mapOf("name" to "아래")),
		)
	}
}
