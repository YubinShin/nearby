package dev.yubin.search.indexer.batch

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.repository.JobRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime

/** job 을 걸었다는 접수증. 색인이 끝난 게 아니라 **시작됐다**는 뜻이다 (HTTP 202). */
data class JobAccepted(
	val jobId: Long,
	val jobName: String,
	val status: String,
	val poll: String,
)

/** step 하나의 진행 상황. 숫자는 전부 Spring Batch 가 센 것이다. */
data class StepProgress(
	val name: String,
	val status: String,
	val read: Long,
	val written: Long,
	val commits: Long,
	val rollbacks: Long,
	val skipped: Long,
	val elapsedMs: Long?,
)

/** `GET /admin/jobs/{id}` 응답. */
data class JobProgress(
	val jobId: Long,
	val jobName: String,
	val status: String,
	val running: Boolean,
	val startedAt: String?,
	val endedAt: String?,
	val elapsedMs: Long?,
	val steps: List<StepProgress>,
	/** 도메인 요약 — 대상 인덱스/컬렉션, watermark, 정리된 버전 등 ([IndexJobs.Ctx]). */
	val summary: Map<String, String>,
	val failure: String?,
)

/**
 * 색인 job 을 **걸고**(비동기) **들여다본다**. (ADR 0013)
 *
 * ### 왜 요청이 색인을 기다리지 않는가
 * 전에는 `POST /admin/vector/reindex` 가 색인이 끝날 때까지 응답하지 않았다. 실측 8분 33초
 * (kind 환경 32분)짜리 작업이라 문제가 셋이었다.
 * 1. `curl` 을 끊으면 **색인도 죽었다.** 요청 스코프에 매달려 있었기 때문이다.
 * 2. 그 취소 경로에서 r2dbc 커넥션 누수가 났다 (`DataRow.release()` 누락 LEAK).
 * 3. 진행 상황을 볼 방법이 로그뿐이었다.
 *
 * 지금은 접수하면 즉시 `202 {jobId}` 를 주고, 색인은 job 스레드에서 계속 돈다. 세 문제가 다
 * 없어진다 — 끊어도 안 죽고, 요청 취소랑 색인 수명이 무관해지고, 진행률은 `BATCH_STEP_EXECUTION`
 * 을 읽어 답한다.
 *
 * ### 진행률을 내가 세지 않는다
 * [progress] 가 돌려주는 읽은 건수·쓴 건수·커밋 수는 **프레임워크가 chunk 커밋마다 DB 에 적은
 * 값**이다. 내가 만든 카운터가 아니다. 그래서 색인기를 재시작해도 지난 실행의 이력이 남아 있고,
 * 실패한 실행이 몇 건까지 갔는지도 나중에 볼 수 있다.
 */
@Service
class IndexJobService(
	private val jobOperator: JobOperator,
	private val jobRepository: JobRepository,
	jobs: List<Job>,
) {

	/**
	 * job 이름 → job. 빈 이름이 아니라 **job 이름**으로 색인한다 ([IndexJobs] 의 상수들).
	 * 벡터 job 은 `psp.vector.enabled=false` 면 아예 없다 — 그때는 이 맵에도 없어서
	 * `/admin/vector` 쪽 엔드포인트가 404 로 정직하게 답한다.
	 */
	private val byName = jobs.associateBy { it.name }

	init {
		log.info("등록된 색인 job: {}", byName.keys.sorted())
	}

	/**
	 * job 을 큐에 넣고 즉시 돌아온다.
	 *
	 * 실행마다 `requestedAt` 을 새로 넣어 **매번 새 JobInstance** 가 되게 한다. 이게 없으면 두 번째
	 * 재색인이 "이미 완료된 인스턴스"로 거절된다 (이유는 [IndexJobs.PARAM_REQUESTED_AT]).
	 *
	 * 이미 job 이 돌고 있으면 거절하지 않고 **줄을 세운다** — 8분짜리 전체 재색인 도중 증분 주기가
	 * 와도 놓치지 않게. 큐는 [BatchConfig] 의 단일 스레드 풀에 있고, 그래서 두 색인이 같은 인덱스를
	 * 동시에 만지는 일이 없다.
	 */
	fun launch(jobName: String, trigger: String = IndexJobs.TRIGGER_MANUAL): JobAccepted {
		val job = byName[jobName] ?: error("그런 색인 job 이 없습니다: $jobName")

		val parameters = JobParametersBuilder()
			.addLocalDateTime(IndexJobs.PARAM_REQUESTED_AT, LocalDateTime.now())
			.addString(IndexJobs.PARAM_TRIGGER, trigger, false)   // false = 식별에 쓰지 않음
			.toJobParameters()

		val execution = jobOperator.start(job, parameters)
		log.info("색인 job 접수 — {} #{} (트리거: {})", jobName, execution.id, trigger)

		return JobAccepted(
			jobId = execution.id,
			jobName = jobName,
			status = execution.status.name,
			poll = "/admin/jobs/${execution.id}",
		)
	}

	/** 진행 상황·결과 조회. 없는 id 면 null. */
	fun progress(jobId: Long): JobProgress? {
		val execution = jobRepository.getJobExecution(jobId) ?: return null

		return JobProgress(
			jobId = execution.id,
			jobName = execution.jobInstance.jobName,
			status = execution.status.name,
			running = execution.status.isRunning,
			startedAt = execution.startTime?.toString(),
			endedAt = execution.endTime?.toString(),
			elapsedMs = elapsedMillis(execution.startTime, execution.endTime),
			// step 은 실행 순서대로 — Set 으로 오기 때문에 시작 시각으로 정렬한다.
			steps = execution.stepExecutions
				.sortedBy { it.startTime ?: LocalDateTime.MAX }
				.map { step ->
					StepProgress(
						name = step.stepName,
						status = step.status.name,
						read = step.readCount,
						written = step.writeCount,
						commits = step.commitCount,
						rollbacks = step.rollbackCount,
						skipped = step.skipCount,
						elapsedMs = elapsedMillis(step.startTime, step.endTime),
					)
				},
			summary = summaryOf(execution),
			failure = failureOf(execution),
		)
	}

	/** 최근 실행 이력. 어떤 job 이 언제 돌았는지 한눈에 — 전에는 로그를 뒤져야 알던 것. */
	fun recent(jobName: String, limit: Int): List<JobProgress> =
		jobRepository.getJobInstances(jobName, 0, limit)
			.flatMap { jobRepository.getJobExecutions(it) }
			.sortedByDescending { it.createTime }
			.take(limit)
			.mapNotNull { progress(it.id) }

	/** 아직 안 끝난 실행이 몇 개인지 — 스케줄러가 겹침을 판단할 때 쓴다. */
	fun runningCount(jobName: String): Int =
		jobRepository.findRunningJobExecutions(jobName).size

	// ---- 내부 ----

	/**
	 * `ExecutionContext` 에서 도메인 요약만 골라낸다. 프레임워크가 넣은 내부 키
	 * (`batch.taskletType` 등)는 응답에 섞지 않는다 — 우리가 정한 키만 화이트리스트로 꺼낸다.
	 */
	private fun summaryOf(execution: org.springframework.batch.core.job.JobExecution): Map<String, String> {
		val ctx = execution.executionContext
		return SUMMARY_KEYS
			.mapNotNull { key -> ctx.get(key)?.toString()?.ifEmpty { null }?.let { key to it } }
			.toMap()
	}

	/**
	 * 실패 이유. `exitDescription` 에는 스택트레이스가 통째로 들어있을 수 있어 첫 줄만 쓴다 —
	 * 전체는 로그에 있다.
	 */
	private fun failureOf(execution: org.springframework.batch.core.job.JobExecution): String? {
		if (!execution.status.isUnsuccessful) return null
		execution.allFailureExceptions.firstOrNull()?.let { return "${it::class.simpleName}: ${it.message}" }
		return execution.exitStatus.exitDescription.lineSequence().firstOrNull()?.ifEmpty { null }
	}

	private fun elapsedMillis(from: LocalDateTime?, to: LocalDateTime?): Long? {
		if (from == null) return null
		return Duration.between(from, to ?: LocalDateTime.now()).toMillis()
	}

	private companion object {
		val log = LoggerFactory.getLogger(IndexJobService::class.java)

		val SUMMARY_KEYS = listOf(
			IndexJobs.Ctx.SEARCH_INDEX,
			IndexJobs.Ctx.SUGGEST_INDEX,
			IndexJobs.Ctx.COLLECTION,
			IndexJobs.Ctx.SINCE,
			IndexJobs.Ctx.CHECKPOINT,
			IndexJobs.Ctx.MAX_UPDATED_AT,
			IndexJobs.Ctx.READ,
			IndexJobs.Ctx.UPSERTED,
			IndexJobs.Ctx.DELETED,
			IndexJobs.Ctx.EMBED_MS,
			IndexJobs.Ctx.REMOVED,
		)
	}
}
