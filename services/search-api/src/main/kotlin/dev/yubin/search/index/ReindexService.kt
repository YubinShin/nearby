package dev.yubin.search.index

import dev.yubin.search.core.place.PlaceDocuments
import dev.yubin.search.core.place.PlaceRow
import co.elastic.clients.elasticsearch.ElasticsearchClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/** 무중단 전체 재색인 결과 요약. */
data class RebuildResult(
	val read: Int,
	val searchIndexed: Int,
	val suggestIndexed: Int,
	val searchIndex: String,
	val suggestIndex: String,
	val removed: List<String>,
)

/** 증분 색인 결과 요약. */
data class IncrementalResult(
	val since: String?,   // 이 시각 이후 바뀐 것만 색인 (null=체크포인트 없어 전체)
	val matched: Int,     // 델타로 읽은 행 수
	val upserted: Int,    // 그중 넣거나 고친 것
	val deleted: Int,     // 그중 지운 것
	val checkpoint: String?, // 이번 실행 후 전진한 watermark
	val searchIndex: String,
	val suggestIndex: String,
)

/**
 * 원천 창고(PostGIS) → 검색 인덱스(ES) 재색인을 지휘한다.
 *
 * - **전체(rebuildAndSwap)**: 새 버전 생성 → 적재 → alias 원자적 스왑 → 옛 버전 삭제 (무중단, ADR 0002).
 *   적재가 실패하면 방금 만든 새 버전을 정리한다(고아 인덱스 방지).
 * - **증분(incremental)**: 체크포인트 이후 바뀐 행만 현재 alias 인덱스에 upsert/delete (멱등, ADR 0001).
 *
 * 쓰기(색인) 경로라 `psp.role.indexer` 로 켜고 끈다 — 질의 전용 노드에는 이 빈이 뜨지 않는다.
 */
@Service
@ConditionalOnProperty(prefix = "psp.role", name = ["indexer"], havingValue = "true", matchIfMissing = true)
class ReindexService(
	private val reader: PlaceR2dbcReader,
	private val indexer: EsBulkIndexer,
	private val admin: IndexAdminService,
	private val checkpoints: CheckpointStore,
	private val es: ElasticsearchClient,
	@Value("\${psp.index.search-alias}") private val searchAlias: String,
	@Value("\${psp.index.suggest-alias}") private val suggestAlias: String,
	@Value("\${psp.index.batch-size}") private val batchSize: Int,
) {

	// ---- 전체 재색인 (무중단 버전 스왑) ----

	suspend fun rebuildAndSwap(): RebuildResult {
		val newSearch = admin.createNextVersion(searchAlias, "es/place_search.json")
		val newSuggest = admin.createNextVersion(suggestAlias, "es/place_suggest.json")

		val stats = try {
			val s = indexFlow(reader.readAll(), newSearch, newSuggest)
			refresh(newSearch, newSuggest)
			s
		} catch (e: Exception) {
			// 스왑 전에 실패 → 서빙엔 영향 없지만 새 인덱스가 고아로 남는다. 정리하고 예외 전파.
			admin.deleteIndices(setOf(newSearch, newSuggest))
			throw e
		}

		val oldSearch = admin.swapAlias(searchAlias, newSearch)
		val oldSuggest = admin.swapAlias(suggestAlias, newSuggest)
		val removed = oldSearch + oldSuggest
		admin.deleteIndices(removed)

		// 전체 재색인 시점을 체크포인트로 심어, 이후 증분이 여기서부터 이어지게 한다.
		stats.maxUpdatedAt?.let { checkpoints.set(PIPELINE, it) }

		return RebuildResult(
			read = stats.read,
			searchIndexed = stats.searchApplied,
			suggestIndexed = stats.suggestApplied,
			searchIndex = newSearch,
			suggestIndex = newSuggest,
			removed = removed.sorted(),
		)
	}

	// ---- 증분 재색인 (바뀐 것만) ----

	suspend fun incremental(): IncrementalResult {
		val searchIndex = admin.indicesBehind(searchAlias).firstOrNull()
			?: error("alias 미설정: $searchAlias — 먼저 전체 재색인이 필요합니다")
		val suggestIndex = admin.indicesBehind(suggestAlias).firstOrNull()
			?: error("alias 미설정: $suggestAlias")

		// watermark: 저장된 체크포인트. 없으면(첫 실행) 인덱스 max 로 폴백.
		val since = checkpoints.get(PIPELINE) ?: admin.maxUpdatedAt(searchAlias)
		val flow = if (since == null) reader.readAll() else reader.readSince(since)

		// alias 로 직접 upsert/delete — 같은 place_id 면 덮어쓰기/삭제라 재실행에 안전(멱등).
		val stats = indexFlow(flow, searchAlias, suggestAlias)
		refresh(searchAlias, suggestAlias)

		// 처리한 델타의 최신 시각까지 watermark 전진 (삭제 행도 여기에 포함되어 전진됨).
		val advanced = stats.maxUpdatedAt?.also { if (since == null || it.isAfter(since)) checkpoints.set(PIPELINE, it) }

		return IncrementalResult(
			since = since?.toString(),
			matched = stats.read,
			upserted = stats.searchApplied - stats.deleted,   // 본문 기준: 적용분에서 삭제 제외 = 실제 upsert
			deleted = stats.deleted,
			checkpoint = (advanced ?: since)?.toString(),
			searchIndex = searchIndex,
			suggestIndex = suggestIndex,
		)
	}

	// ---- 공통: 스트림을 배치로 나눠 두 인덱스에 upsert/delete ----

	private suspend fun indexFlow(flow: Flow<PlaceRow>, searchTarget: String, suggestTarget: String): LoadStats {
		val stats = LoadStats()
		val batch = ArrayList<PlaceRow>(batchSize)

		suspend fun flush() {
			if (batch.isEmpty()) return
			val s = indexer.bulk(searchTarget, batch.map { action(it) { r -> PlaceDocuments.searchDoc(r) } })
			val g = indexer.bulk(suggestTarget, batch.map { action(it) { r -> PlaceDocuments.suggestDoc(r) } })
			stats.searchApplied += s.upserted + s.deleted
			stats.suggestApplied += g.upserted + g.deleted
			stats.deleted += s.deleted
			stats.read += batch.size
			batch.forEach { r -> if (stats.maxUpdatedAt == null || r.updatedAt.isAfter(stats.maxUpdatedAt)) stats.maxUpdatedAt = r.updatedAt }
			batch.clear()
		}

		flow.collect { row ->
			batch.add(row)
			if (batch.size >= batchSize) flush()
		}
		flush()
		return stats
	}

	/** 소프트 삭제면 Delete, 아니면 Upsert. */
	private inline fun action(row: PlaceRow, doc: (PlaceRow) -> Map<String, Any?>): BulkAction =
		if (row.deletedAt != null) BulkAction.Delete(row.placeId) else BulkAction.Upsert(row.placeId, doc(row))

	/** 색인 직후 바로 검색되도록 refresh (데모 편의). */
	private suspend fun refresh(vararg indices: String) = withContext(Dispatchers.IO) {
		es.indices().refresh { it.index(indices.toList()) }
	}

	private class LoadStats {
		var read = 0
		var searchApplied = 0
		var suggestApplied = 0
		var deleted = 0
		var maxUpdatedAt: OffsetDateTime? = null
	}

	companion object {
		private const val PIPELINE = CheckpointStore.PLACE_PIPELINE
	}
}
