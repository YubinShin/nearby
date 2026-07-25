package dev.yubin.search.indexer.batch

import dev.yubin.search.core.place.PlaceRow
import org.slf4j.LoggerFactory
import org.springframework.batch.core.listener.ChunkListener
import org.springframework.batch.infrastructure.item.Chunk

/**
 * chunk 를 하나 커밋할 때마다 진행 상황을 한 줄 남긴다.
 *
 * ### 왜 진행 로그가 있어야 하나
 * 벡터 전체 재색인은 실측 8분 33초(kind 환경 32분)다. 그동안 로그가 조용하면 **멈춘 건지 느린
 * 건지 구분할 방법이 없다** — 실제로 이게 없어서 thread dump 를 떠서 확인해야 했다. 초당 처리량이
 * 함께 찍히면 남은 시간을 눈대중할 수 있다.
 *
 * ### 진행 상황을 보는 길이 두 개로 늘었다
 * - **로그** (이 클래스) — 사람이 `tail` 로 본다. 프로세스가 죽으면 같이 끝난다.
 * - **DB** — Spring Batch 가 chunk 커밋마다 `BATCH_STEP_EXECUTION` 에 읽은/쓴/커밋 건수를 적는다.
 *   `GET /admin/jobs/{id}` 가 그걸 읽는다. **이건 전에 없던 것이다** — 색인기를 재시작해도, 실패한
 *   실행이 몇 건까지 갔는지도 남는다.
 *
 * 그래서 이 리스너는 **자기가 센 것만** 말한다(이번 실행에서 이 객체가 본 chunk). 누적 이력은
 * 프레임워크 쪽이 더 정확하니 그쪽에 맡기고, 여기서는 "지금 살아 움직이고 있다"만 보여준다.
 *
 * ### 새 chunk 리스너 API 를 쓴다
 * Batch 6 은 chunk step 구현을 `ChunkOrientedStep` 으로 갈면서 리스너 시그니처도 바꿨다 —
 * `ChunkContext` 를 받던 옛 메서드들은 deprecated 이고, 지금은 처리된 [Chunk] 를 직접 받는다.
 * 실제로 이게 더 낫다: 필요한 게 "이번에 몇 건 처리했나"인데, 전에는 그걸 알려고 `ChunkContext`
 * → `StepContext` → `StepExecution` 세 단계를 타고 들어가야 했다.
 */
class ChunkProgressLogger(private val label: String) : ChunkListener<PlaceRow, PlaceRow> {

	private var startedAt = 0L
	private var chunks = 0L
	private var items = 0L

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

	/**
	 * chunk 가 실패하면 그 트랜잭션은 롤백된다 — **직전 커밋 지점까지는 남는다.**
	 * 어디서 멈췄는지 로그에 남겨야 재실행 범위를 판단할 수 있다.
	 */
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
