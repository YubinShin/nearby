package dev.yubin.search.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation
import co.elastic.clients.elasticsearch.core.bulk.OperationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

/** 색인 동작 한 건: 문서를 넣거나(Upsert) 지운다(Delete). 둘 다 id 기준이라 재실행에 안전(멱등). */
sealed interface BulkAction {
	val id: String

	data class Upsert(override val id: String, val source: Map<String, Any?>) : BulkAction
	data class Delete(override val id: String) : BulkAction
}

/** bulk 한 번의 적용 결과. */
data class BulkStats(val upserted: Int, val deleted: Int)

/**
 * ES `_bulk` 로 색인 동작 뭉텅이를 한 번에 적용한다.
 * 같은 id upsert 는 덮어쓰기, delete 는 제거 — 둘 다 멱등이라 재처리/재전송에 안전 (ADR 0001).
 */
@Component
class EsBulkIndexer(private val es: ElasticsearchClient) {

	suspend fun bulk(index: String, actions: List<BulkAction>): BulkStats = withContext(Dispatchers.IO) {
		if (actions.isEmpty()) return@withContext BulkStats(0, 0)

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
			// 없는 문서 삭제(404)는 정상(멱등) — 진짜 실패만 예외로.
			val fatal = resp.items().firstOrNull {
				it.error() != null && !(it.operationType() == OperationType.Delete && it.status() == 404)
			}
			if (fatal != null) throw IllegalStateException("bulk 색인 실패 ($index): ${fatal.error()?.reason()}")
		}

		BulkStats(
			upserted = actions.count { it is BulkAction.Upsert },
			deleted = actions.count { it is BulkAction.Delete },
		)
	}
}
