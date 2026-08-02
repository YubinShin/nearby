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

		val (alive, dead) = rows.partition { it.indexable }

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

		qdrant.delete(target, dead.map { it.placeId })

		progress.embedMillis += embedNanos / 1_000_000
		progress.record(rows, upserted = alive.size, deleted = dead.size)
	}

	override fun afterStep(stepExecution: StepExecution): ExitStatus? {
		progress.promoteTo(stepExecution)
		return null
	}
}
