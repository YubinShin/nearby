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
						append("[$pipeline] 색인된 데이터와 이 프로세스의 계약이 다릅니다.\n")
						verdict.differences.forEach { append("  - ").append(it).append('\n') }
						append("  이 상태로는 오류 없이 결과만 조용히 틀려집니다.\n")
						append("  → ").append(remedy)
					},
				)
		}
	}

	private companion object {
		const val HTTP_NOT_FOUND = 404

		private val log = LoggerFactory.getLogger(IndexMetaStore::class.java)
	}
}
