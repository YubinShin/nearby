package dev.yubin.search.indexer.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.Refresh
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

data class Checkpoint(val last_updated_at: String? = null)

@Component
class CheckpointStore(
	private val es: ElasticsearchClient,
	@Value("\${psp.index.checkpoint-index}") private val index: String,
) {
	fun get(pipeline: String): OffsetDateTime? =
		try {
			val resp = es.get({ g -> g.index(index).id(pipeline) }, Checkpoint::class.java)
			resp.source()?.last_updated_at?.let(OffsetDateTime::parse)
		} catch (e: ElasticsearchException) {
			if (e.status() == HTTP_NOT_FOUND) null else throw e
		}

	fun set(pipeline: String, at: OffsetDateTime) {
		es.index { i ->
			i.index(index).id(pipeline).document(Checkpoint(at.toString())).refresh(Refresh.True)
		}
	}

	companion object {
		const val PLACE_PIPELINE = "place"

		const val PLACE_VECTOR = "place_vector"

		private const val HTTP_NOT_FOUND = 404
	}
}
