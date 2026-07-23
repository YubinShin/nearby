package dev.yubin.search.vector

import dev.yubin.search.embed.EmbeddingModel
import dev.yubin.search.embed.PlaceVectorText
import dev.yubin.search.index.CheckpointStore
import dev.yubin.search.index.PlaceR2dbcReader
import dev.yubin.search.index.PlaceRow
import kotlinx.coroutines.flow.Flow
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/** 벡터 전체 재색인 결과 요약. */
data class VectorRebuildResult(
	val read: Int,
	val upserted: Int,
	val deleted: Int,
	val collection: String,
	val points: Long,
	val removed: List<String>,
	val elapsedMs: Long,
	val embedMs: Long,
)

/** 벡터 증분 색인 결과 요약. */
data class VectorIncrementalResult(
	val since: String?,
	val matched: Int,
	val upserted: Int,
	val deleted: Int,
	val checkpoint: String?,
	val collection: String,
	val elapsedMs: Long,
)

/**
 * 원천 창고(PostGIS) → 벡터 엔진(Qdrant) 색인.
 *
 * 키워드 색인([dev.yubin.search.index.ReindexService])과 **일부러 따로 돌린다.**
 * - 임베딩 추론은 초당 수천 건 수준이라 ES bulk 보다 훨씬 느리다. 한 파이프라인에 묶으면
 *   느린 쪽이 빠른 쪽의 발목을 잡는다.
 * - 모델을 바꾸면 벡터만 전부 다시 만들어야 한다. 그때 키워드 인덱스까지 재색인할 이유가 없다.
 * 그래서 체크포인트도 별도 키([CheckpointStore.PLACE_VECTOR])로 따로 전진시킨다.
 */
@Service
@ConditionalOnProperty(
	name = ["psp.role.indexer", "psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class VectorIndexService(
	private val reader: PlaceR2dbcReader,
	private val embeddings: EmbeddingModel,
	private val qdrant: QdrantStore,
	private val checkpoints: CheckpointStore,
	@Value("\${psp.vector.alias}") private val alias: String,
	@Value("\${psp.vector.batch-size}") private val batchSize: Int,
	@Value("\${psp.embedding.batch-size}") private val embedBatch: Int,
) {

	suspend fun rebuildAndSwap(): VectorRebuildResult {
		val startedAt = System.nanoTime()
		val newCollection = qdrant.createNextVersion(alias, embeddings.dimension)

		val stats = try {
			load(reader.readAll(), newCollection)
		} catch (e: Exception) {
			// 스왑 전 실패 → 서빙엔 영향 없다. 고아 컬렉션만 정리하고 예외를 올린다.
			qdrant.deleteCollections(setOf(newCollection))
			throw e
		}

		val removed = qdrant.swapAlias(alias, newCollection)
		qdrant.deleteCollections(removed)
		stats.maxUpdatedAt?.let { checkpoints.set(CheckpointStore.PLACE_VECTOR, it) }

		return VectorRebuildResult(
			read = stats.read,
			upserted = stats.upserted,
			deleted = stats.deleted,
			collection = newCollection,
			points = qdrant.count(newCollection),
			removed = removed.sorted(),
			elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
			embedMs = stats.embedNanos / 1_000_000,
		)
	}

	suspend fun incremental(): VectorIncrementalResult {
		val startedAt = System.nanoTime()
		val collection = qdrant.collectionsBehind(alias).firstOrNull()
			?: error("alias 미설정: $alias — 먼저 벡터 전체 재색인이 필요합니다")

		val since = checkpoints.get(CheckpointStore.PLACE_VECTOR)
		val stats = load(if (since == null) reader.readAll() else reader.readSince(since), alias)

		val advanced = stats.maxUpdatedAt
			?.also { if (since == null || it.isAfter(since)) checkpoints.set(CheckpointStore.PLACE_VECTOR, it) }

		return VectorIncrementalResult(
			since = since?.toString(),
			matched = stats.read,
			upserted = stats.upserted,
			deleted = stats.deleted,
			checkpoint = (advanced ?: since)?.toString(),
			collection = collection,
			elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
		)
	}

	// ---- 공통: 스트림을 배치로 나눠 임베딩 → upsert/delete ----

	private suspend fun load(flow: Flow<PlaceRow>, target: String): LoadStats {
		val stats = LoadStats()
		val batch = ArrayList<PlaceRow>(batchSize)

		suspend fun flush() {
			if (batch.isEmpty()) return
			val (alive, dead) = batch.partition { it.deletedAt == null }

			// 임베딩은 배치로 넣어야 빠르다. 다만 한 번에 너무 많이 넣으면 메모리가 튀어서
			// 읽기 배치(batchSize)와 추론 배치(embedBatch)를 따로 둔다.
			alive.chunked(embedBatch).forEach { chunk ->
				val at = System.nanoTime()
				val vectors = embeddings.embedPassages(chunk.map { PlaceVectorText.of(it) })
				stats.embedNanos += System.nanoTime() - at
				qdrant.upsert(target, chunk.zip(vectors) { row, v -> VectorPoint(row.placeId, v, PlaceVectors.payload(row)) })
			}
			qdrant.delete(target, dead.map { it.placeId })

			stats.read += batch.size
			stats.upserted += alive.size
			stats.deleted += dead.size
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

	private class LoadStats {
		var read = 0
		var upserted = 0
		var deleted = 0
		var embedNanos = 0L
		var maxUpdatedAt: OffsetDateTime? = null
	}
}
