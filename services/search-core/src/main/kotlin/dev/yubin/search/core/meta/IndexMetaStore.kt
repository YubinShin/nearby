package dev.yubin.search.core.meta

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.Refresh
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class IndexMetaStore(private val es: ElasticsearchClient) {
	fun write(pipeline: String, stamp: IndexMeta.Stamp) {
		es.index { it.index(IndexMeta.ES_INDEX).id(pipeline).document(stamp).refresh(Refresh.True) }
	}

	fun read(pipeline: String): IndexMeta.Stamp? =
		try {
			es.get({ g -> g.index(IndexMeta.ES_INDEX).id(pipeline) }, IndexMeta.Stamp::class.java).source()
		} catch (e: ElasticsearchException) {
			if (e.status() == HTTP_NOT_FOUND) null else throw e
		}

	fun requireCompatible(pipeline: String, expected: IndexMeta.Stamp, remedy: String) {
		when (val verdict = IndexMeta.verify(read(pipeline), expected)) {
			IndexMeta.Verdict.Ok -> Unit

			IndexMeta.Verdict.Missing ->
				log.warn(
					"[{}] no version stamp — the index predates the module split (ADR 0011). " +
						"one full reindex writes the stamp and clears this warning.",
					pipeline,
				)

			is IndexMeta.Verdict.Mismatch ->
				throw IllegalStateException(
					buildString {
						append("[$pipeline] the indexed data and this process disagree on the contract.\n")
						verdict.differences.forEach { append("  - ").append(it).append('\n') }
						append("  in this state nothing throws — the results just go silently wrong.\n")
						append("  → ").append(if (verdict.sharesSchemaVersion()) REMEDY_SCHEMA_VERSION else remedy)
					},
				)
		}
	}

	private companion object {
		const val REMEDY_SCHEMA_VERSION =
			"the schema version is shared by every pipeline, so one reindex is not enough. " +
				"run both on the indexer (indexer-batch) and then start this app again: " +
				"POST /admin/reindex, then POST /admin/vector/reindex."

		const val HTTP_NOT_FOUND = 404

		private val log = LoggerFactory.getLogger(IndexMetaStore::class.java)
	}
}
