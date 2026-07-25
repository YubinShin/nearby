package dev.yubin.search.indexer.batch

import dev.yubin.search.core.place.PlaceRow
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.ExecutionContext
import java.time.OffsetDateTime

/**
 * 적재 step 이 쌓아가는 도메인 집계를 **`ExecutionContext` 에 얹어** 다룬다. (ADR 0013)
 *
 * ### 왜 필드가 아니라 ExecutionContext 인가
 * 전에는 `LoadStats` 라는 지역 객체에 세어 담고 함수 끝에서 반환했다. step 으로 쪼개면 그게 안
 * 된다 — 세는 곳(load step)과 쓰는 곳(promote step)이 **다른 트랜잭션**이고, 그 사이에 프로세스가
 * 죽을 수도 있다. 그래서 프레임워크가 chunk 커밋마다 함께 저장해주는 이 맵에 담는다.
 *
 * 부수 효과가 이득이다: 실패한 실행도 `BATCH_STEP_EXECUTION_CONTEXT` 에 "어디까지 했는지"가
 * 남는다. 전에는 로그를 뒤져야 알던 값이다.
 *
 * ### 프레임워크가 이미 세는 건 여기 없다
 * 읽은 건수·쓴 건수·커밋 수·롤백 수는 Spring Batch 가 `BATCH_STEP_EXECUTION` 에 직접 센다.
 * 여기 담는 건 **프레임워크가 알 수 없는 것**뿐이다: 어디까지의 변경을 반영했나(watermark),
 * upsert 와 delete 의 비율, 임베딩에 쓴 시간.
 */
class LoadProgress(private val ctx: ExecutionContext) {

	var read: Long
		get() = ctx.getLong(IndexJobs.Ctx.READ, 0L)
		private set(v) = ctx.putLong(IndexJobs.Ctx.READ, v)

	var upserted: Long
		get() = ctx.getLong(IndexJobs.Ctx.UPSERTED, 0L)
		private set(v) = ctx.putLong(IndexJobs.Ctx.UPSERTED, v)

	var deleted: Long
		get() = ctx.getLong(IndexJobs.Ctx.DELETED, 0L)
		private set(v) = ctx.putLong(IndexJobs.Ctx.DELETED, v)

	var embedMillis: Long
		get() = ctx.getLong(IndexJobs.Ctx.EMBED_MS, 0L)
		set(v) = ctx.putLong(IndexJobs.Ctx.EMBED_MS, v)

	/** 이번 적재가 본 가장 최신 `updated_at`. 이게 다음 증분의 출발점이 된다. */
	var maxUpdatedAt: OffsetDateTime?
		get() = ctx.getString(IndexJobs.Ctx.MAX_UPDATED_AT, "").ifEmpty { null }?.let(OffsetDateTime::parse)
		private set(v) = ctx.putString(IndexJobs.Ctx.MAX_UPDATED_AT, v?.toString() ?: "")

	/**
	 * chunk 하나를 처리한 결과를 더한다.
	 *
	 * **삭제 행도 watermark 를 전진시킨다** — 이게 체크포인트를 인덱스 밖에 따로 두는 이유였다
	 * ([dev.yubin.search.indexer.index.CheckpointStore] 주석). 지운 행을 빼고 세면 watermark 가
	 * 삭제 지점을 못 넘어가 매번 같은 행을 다시 지우려 한다.
	 */
	fun record(rows: List<PlaceRow>, upserted: Int, deleted: Int) {
		read += rows.size
		this.upserted += upserted
		this.deleted += deleted

		val batchMax = rows.maxOfOrNull { it.updatedAt } ?: return
		val current = maxUpdatedAt
		if (current == null || batchMax.isAfter(current)) maxUpdatedAt = batchMax
	}

	/**
	 * step 이 끝나면 집계를 **job 수준 컨텍스트로 올린다.** promote step 과 HTTP 상태 조회가
	 * 여기서 읽는다. (step 컨텍스트는 그 step 것이라 다음 step 이 볼 수 없다.)
	 */
	fun promoteTo(stepExecution: StepExecution) {
		val job = stepExecution.jobExecution.executionContext
		job.putLong(IndexJobs.Ctx.READ, read)
		job.putLong(IndexJobs.Ctx.UPSERTED, upserted)
		job.putLong(IndexJobs.Ctx.DELETED, deleted)
		job.putLong(IndexJobs.Ctx.EMBED_MS, embedMillis)
		maxUpdatedAt?.let { job.putString(IndexJobs.Ctx.MAX_UPDATED_AT, it.toString()) }
	}

	companion object {
		/** load step 안에서 쓰는 진행 상황 — step 자신의 컨텍스트에 담는다. */
		fun of(stepExecution: StepExecution) = LoadProgress(stepExecution.executionContext)

		/** promote step·HTTP 응답에서 읽는 최종 집계 — job 컨텍스트에서 꺼낸다. */
		fun ofJob(stepExecution: StepExecution) = LoadProgress(stepExecution.jobExecution.executionContext)
	}
}
