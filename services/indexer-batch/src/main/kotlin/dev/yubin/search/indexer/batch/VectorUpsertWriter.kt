package dev.yubin.search.indexer.batch

import dev.yubin.search.core.embed.EmbeddingModel
import dev.yubin.search.core.embed.PlaceVectorText
import dev.yubin.search.core.place.PlaceRow
import dev.yubin.search.core.vector.PlaceVectorPayload
import dev.yubin.search.core.vector.VectorPoint
import dev.yubin.search.indexer.vector.QdrantIndexStore
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

/**
 * chunk 하나를 임베딩해 Qdrant 에 올린다. 삭제된 행은 점을 지운다.
 *
 * ### 왜 임베딩이 `ItemProcessor` 가 아니라 writer 에 있나
 * 모양으로만 보면 "행 → 벡터" 변환은 `ItemProcessor` 자리다. 그런데 Spring Batch 의 processor 는
 * **한 건씩** 부른다. 임베딩은 배치로 넣어야 빠르다 — 문장 64개를 한 번에 추론기에 넣는 것과
 * 한 개씩 64번 넣는 것은 몇 배 차이가 난다(ONNX 배치 추론이 행렬 연산을 뭉쳐서 한다).
 *
 * 색인 시간의 **96.1%가 임베딩 추론**인 파이프라인에서 그걸 포기하면 리팩터가 성능 퇴행이 된다.
 * 그래서 chunk 전체를 한꺼번에 볼 수 있는 writer 에서 배치 추론을 한다. 모양보다 실측을 따랐다.
 *
 * ### 읽기 배치와 추론 배치를 따로 두는 이유
 * chunk 크기(`psp.vector.batch-size`, 500)는 **트랜잭션·재시작 단위**고, 추론 배치
 * (`psp.embedding.batch-size`, 64)는 **메모리 단위**다. 한 번에 500개를 추론기에 넣으면 메모리가
 * 튀고, 64개마다 커밋하면 메타데이터 갱신이 잦아진다. 손잡이를 둘로 나눠 각각 맞춘다.
 *
 * `open` 인 이유는 [KeywordBulkWriter] 와 같다 — `@StepScope` 의 CGLIB 프록시가 상속을 요구한다.
 */
open class VectorUpsertWriter(
	private val embeddings: EmbeddingModel,
	private val qdrant: QdrantIndexStore,
	private val target: String,
	private val embedBatch: Int,
) : ItemWriter<PlaceRow>, StepExecutionListener {

	private lateinit var progress: LoadProgress

	override fun beforeStep(stepExecution: StepExecution) {
		progress = LoadProgress.of(stepExecution)
	}

	override fun write(chunk: Chunk<out PlaceRow>) {
		val rows = chunk.items
		if (rows.isEmpty()) return

		val (alive, dead) = rows.partition { it.deletedAt == null }

		var embedNanos = 0L
		alive.chunked(embedBatch).forEach { batch ->
			val at = System.nanoTime()
			val vectors = embeddings.embedPassages(batch.map { PlaceVectorText.of(it) })
			embedNanos += System.nanoTime() - at

			qdrant.upsert(
				target,
				batch.zip(vectors) { row, vector -> VectorPoint(row.placeId, vector, PlaceVectorPayload.of(row)) },
			)
		}

		// 없는 점을 지우는 것도 성공으로 친다 — 멱등이라 재실행에 안전하다.
		qdrant.delete(target, dead.map { it.placeId })

		progress.embedMillis += embedNanos / 1_000_000
		progress.record(rows, upserted = alive.size, deleted = dead.size)
	}

	override fun afterStep(stepExecution: StepExecution): ExitStatus? {
		progress.promoteTo(stepExecution)
		return null
	}
}
