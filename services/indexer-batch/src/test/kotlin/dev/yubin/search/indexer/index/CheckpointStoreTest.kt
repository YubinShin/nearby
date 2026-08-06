package dev.yubin.search.indexer.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.ErrorResponse
import co.elastic.clients.elasticsearch.core.GetRequest
import co.elastic.clients.util.ObjectBuilder
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import java.util.function.Function
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CheckpointStoreTest {
	private val es = mock(ElasticsearchClient::class.java)
	private val store = CheckpointStore(es, "psp_index_checkpoint")

	private fun errorResponse(status: Int): ErrorResponse =
		ErrorResponse.of { r -> r.status(status).error { c -> c.type("test_error").reason("test") } }

	private fun stubGetToThrow(status: Int) {
		doThrow(ElasticsearchException("get", errorResponse(status)))
			.`when`(es)
			.get(
				ArgumentMatchers.any<Function<GetRequest.Builder, ObjectBuilder<GetRequest>>>(),
				ArgumentMatchers.eq(Checkpoint::class.java),
			)
	}

	@Test
	fun `a missing checkpoint index reads as no watermark`() {
		stubGetToThrow(404)

		assertNull(store.get(CheckpointStore.PLACE_PIPELINE))
	}

	@Test
	fun `a transient elasticsearch failure propagates instead of reading as no watermark`() {
		stubGetToThrow(503)

		assertFailsWith<ElasticsearchException> { store.get(CheckpointStore.PLACE_PIPELINE) }
	}

	@Test
	fun `a rejected checkpoint read propagates instead of reading as no watermark`() {
		stubGetToThrow(429)

		assertFailsWith<ElasticsearchException> { store.get(CheckpointStore.PLACE_VECTOR) }
	}
}
