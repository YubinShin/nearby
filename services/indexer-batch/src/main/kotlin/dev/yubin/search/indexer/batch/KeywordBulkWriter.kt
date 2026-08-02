package dev.yubin.search.indexer.batch

import dev.yubin.search.core.place.PlaceDocuments
import dev.yubin.search.core.place.PlaceRow
import dev.yubin.search.indexer.index.BulkAction
import dev.yubin.search.indexer.index.EsBulkIndexer
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

open class KeywordBulkWriter(
	private val indexer: EsBulkIndexer,
	private val searchTarget: String,
	private val suggestTarget: String,
) : ItemWriter<PlaceRow>, StepExecutionListener {
	private lateinit var progress: LoadProgress

	override fun beforeStep(stepExecution: StepExecution) {
		progress = LoadProgress.of(stepExecution)
	}

	override fun write(chunk: Chunk<out PlaceRow>) {
		val rows = chunk.items
		if (rows.isEmpty()) return

		val searchStats = indexer.bulk(searchTarget, rows.map { action(it, PlaceDocuments::searchDoc) })
		indexer.bulk(suggestTarget, rows.map { action(it, PlaceDocuments::suggestDoc) })

		progress.record(rows, upserted = searchStats.upserted, deleted = searchStats.deleted)
	}

	override fun afterStep(stepExecution: StepExecution): ExitStatus? {
		progress.promoteTo(stepExecution)
		return null
	}

	private fun action(row: PlaceRow, doc: (PlaceRow) -> Map<String, Any?>): BulkAction =
		if (row.indexable) BulkAction.Upsert(row.placeId, doc(row)) else BulkAction.Delete(row.placeId)
}
