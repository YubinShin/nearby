package dev.yubin.search.indexer.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation
import co.elastic.clients.elasticsearch.core.bulk.OperationType
import org.springframework.stereotype.Component

sealed interface BulkAction {
	val id: String

	data class Upsert(override val id: String, val source: Map<String, Any?>) : BulkAction
	data class Delete(override val id: String) : BulkAction
}

data class BulkStats(val upserted: Int, val deleted: Int)

@Component
class EsBulkIndexer(private val es: ElasticsearchClient) {
	fun bulk(index: String, actions: List<BulkAction>): BulkStats {
		if (actions.isEmpty()) return BulkStats(0, 0)

		val ops = actions.map { a ->
			when (a) {
				is BulkAction.Upsert -> BulkOperation.of { b ->
					b.index(IndexOperation.of<Map<String, Any?>> { io -> io.index(index).id(a.id).document(a.source) })
				}
				is BulkAction.Delete -> BulkOperation.of { b ->
					b.delete { d -> d.index(index).id(a.id) }
				}
			}
		}

		val resp = es.bulk(BulkRequest.of { it.operations(ops) })
		if (resp.errors()) {
			val fatal = resp.items().firstOrNull {
				it.error() != null && !(it.operationType() == OperationType.Delete && it.status() == 404)
			}
			if (fatal != null) throw IllegalStateException("bulk 색인 실패 ($index): ${fatal.error()?.reason()}")
		}

		return BulkStats(
			upserted = actions.count { it is BulkAction.Upsert },
			deleted = actions.count { it is BulkAction.Delete },
		)
	}
}
