package dev.yubin.search.indexer.batch

import dev.yubin.search.core.place.PlaceRow
import org.slf4j.LoggerFactory
import org.springframework.batch.core.listener.ChunkListener
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.Chunk

class ChunkProgressLogger(private val label: String) :
	ChunkListener<PlaceRow, PlaceRow>, StepExecutionListener {
	private var startedAt = 0L
	private var chunks = 0L
	private var items = 0L

	override fun beforeStep(stepExecution: StepExecution) {
		startedAt = 0L
		chunks = 0L
		items = 0L
	}

	override fun beforeChunk(chunk: Chunk<PlaceRow>) {
		if (startedAt == 0L) {
			startedAt = System.nanoTime()
			log.info("{} started — one chunk is one transaction and one restart unit", label)
		}
	}

	override fun afterChunk(chunk: Chunk<PlaceRow>) {
		chunks++
		items += chunk.size()

		val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
		val rate = if (elapsedSeconds > 0) items / elapsedSeconds else 0.0

		log.info("{} progress: {} rows (chunk #{}) · {} rows/s", label, items, chunks, "%.0f".format(rate))
	}

	override fun onChunkError(exception: Exception, chunk: Chunk<PlaceRow>) {
		log.warn(
			"{} chunk failed — {} rows committed, then a {}-row chunk rolled back: {}",
			label, items, chunk.size(), exception.message,
		)
	}

	private companion object {
		val log = LoggerFactory.getLogger(ChunkProgressLogger::class.java)
	}
}
