package dev.yubin.search.indexer.vector

import dev.yubin.search.core.embed.EmbeddingModel
import dev.yubin.search.core.embed.PlaceVectorText
import dev.yubin.search.core.meta.IndexMeta
import dev.yubin.search.core.meta.IndexMetaStore
import dev.yubin.search.core.place.PlaceRow
import dev.yubin.search.core.vector.PlaceVectorPayload
import dev.yubin.search.core.vector.QdrantStore
import dev.yubin.search.core.vector.VectorPoint
import dev.yubin.search.indexer.index.CheckpointStore
import dev.yubin.search.indexer.index.PlaceR2dbcReader
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
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
	name = ["psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class VectorIndexService(
	private val reader: PlaceR2dbcReader,
	private val embeddings: EmbeddingModel,
	private val qdrant: QdrantStore,
	private val checkpoints: CheckpointStore,
	private val meta: IndexMetaStore,
	@Value("\${psp.vector.alias}") private val alias: String,
	@Value("\${psp.vector.batch-size}") private val batchSize: Int,
	@Value("\${psp.embedding.batch-size}") private val embedBatch: Int,
) {

	private val log = LoggerFactory.getLogger(VectorIndexService::class.java)

	suspend fun rebuildAndSwap(): VectorRebuildResult {
		val startedAt = System.nanoTime()
		val newCollection = qdrant.createNextVersion(alias, embeddings.dimension)

		val stats = try {
			load(reader.readAll(), newCollection, "전체 재색인")
		} catch (e: Exception) {
			// 스왑 전 실패 → 서빙엔 영향 없다. 고아 컬렉션만 정리하고 예외를 올린다.
			qdrant.deleteCollections(setOf(newCollection))
			throw e
		}

		val removed = qdrant.swapAlias(alias, newCollection)
		qdrant.deleteCollections(removed)

		/*
		 * 스왑 성공 후 버전 도장 (ADR 0011). 여기 남기는 값은 설정이 아니라 **실제로 로드된
		 * 모델**이다 — 설정만 맞고 파일이 다른 경우까지 잡으려는 것. 질의기가 기동할 때
		 * 자기 모델과 대조하고, 다르면 뜨지 않는다.
		 */
		meta.write(
			IndexMeta.PIPELINE_VECTOR,
			IndexMeta.stamp(embeddingModel = embeddings.modelId, embeddingDim = embeddings.dimension),
		)

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

		/*
		 * **증분은 살아있는 컬렉션에 그대로 덮어쓴다.** 그래서 모델이 바뀐 채로 증분을 돌리면
		 * 한 컬렉션 안에 옛 모델 벡터와 새 모델 벡터가 섞인다 — 그리고 그 상태는 오류를 내지
		 * 않는다. 384차원끼리라면 유사도 계산은 멀쩡히 되고, 숫자만 의미를 잃는다.
		 *
		 * 섞인 걸 나중에 감지하는 것보다 **애초에 못 섞이게** 막는 쪽이 싸다 (ADR 0011).
		 * 모델을 바꿨으면 답은 하나뿐이다 — 전체 재색인.
		 */
		meta.requireCompatible(
			IndexMeta.PIPELINE_VECTOR,
			IndexMeta.stamp(embeddingModel = embeddings.modelId, embeddingDim = embeddings.dimension),
			remedy = "POST /admin/vector/rebuild 로 전체 재색인하세요. 증분으로는 섞인 컬렉션이 됩니다.",
		)

		val since = checkpoints.get(CheckpointStore.PLACE_VECTOR)
		val stats = load(
			if (since == null) reader.readAll() else reader.readSince(since),
			alias,
			if (since == null) "증분(최초=전체)" else "증분",
		)

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

	private suspend fun load(flow: Flow<PlaceRow>, target: String, label: String): LoadStats {
		val stats = LoadStats()
		val batch = ArrayList<PlaceRow>(batchSize)
		// 진행 로그. 배치(batchSize)마다 한 줄 남긴다 — 임베딩이 느려서 "멈춘 건지 느린 건지"를
		// 로그만으로 가를 수 있어야 한다(실제로 이게 없어서 thread dump 를 떠야 했다).
		val startedAt = System.nanoTime()
		log.info("벡터 {} 시작 → {} (읽기 배치 {}, 추론 배치 {})", label, target, batchSize, embedBatch)

		suspend fun flush() {
			if (batch.isEmpty()) return
			val (alive, dead) = batch.partition { it.deletedAt == null }

			// 임베딩은 배치로 넣어야 빠르다. 다만 한 번에 너무 많이 넣으면 메모리가 튀어서
			// 읽기 배치(batchSize)와 추론 배치(embedBatch)를 따로 둔다.
			alive.chunked(embedBatch).forEach { chunk ->
				val at = System.nanoTime()
				val vectors = embeddings.embedPassages(chunk.map { PlaceVectorText.of(it) })
				stats.embedNanos += System.nanoTime() - at
				qdrant.upsert(target, chunk.zip(vectors) { row, v -> VectorPoint(row.placeId, v, PlaceVectorPayload.of(row)) })
			}
			qdrant.delete(target, dead.map { it.placeId })

			stats.read += batch.size
			stats.upserted += alive.size
			stats.deleted += dead.size
			batch.forEach { r -> if (stats.maxUpdatedAt == null || r.updatedAt.isAfter(stats.maxUpdatedAt)) stats.maxUpdatedAt = r.updatedAt }
			batch.clear()

			// 누적 처리량과 초당 속도. 속도가 있으면 남은 시간을 눈대중할 수 있다.
			val elapsedS = (System.nanoTime() - startedAt) / 1_000_000_000.0
			val rate = if (elapsedS > 0) stats.read / elapsedS else 0.0
			log.info("벡터 {} 진행: {}건 (upsert {}, delete {}) · {}건/초", label, stats.read, stats.upserted, stats.deleted, "%.0f".format(rate))
		}

		flow.collect { row ->
			batch.add(row)
			if (batch.size >= batchSize) flush()
		}
		flush()
		log.info("벡터 {} 완료: {}건 ({}ms, 임베딩 {}ms)", label, stats.read, (System.nanoTime() - startedAt) / 1_000_000, stats.embedNanos / 1_000_000)
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
