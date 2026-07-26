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
			log.info("{} 시작 — chunk 하나가 트랜잭션 하나이고 재시작 단위다", label)
		}
	}

	override fun afterChunk(chunk: Chunk<PlaceRow>) {
		chunks++
		items += chunk.size()

		val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
		val rate = if (elapsedSeconds > 0) items / elapsedSeconds else 0.0

		log.info("{} 진행: {}건 ({}번째 chunk) · {}건/초", label, items, chunks, "%.0f".format(rate))
	}

	override fun onChunkError(exception: Exception, chunk: Chunk<PlaceRow>) {
		log.warn(
			"{} chunk 실패 — {}건까지 커밋된 뒤 {}건짜리 chunk 에서 롤백: {}",
			label, items, chunk.size(), exception.message,
		)
	}

	private companion object {
		val log = LoggerFactory.getLogger(ChunkProgressLogger::class.java)
	}
}
