package dev.yubin.search.indexer.batch

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.repository.JobRepository
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Duration
import java.time.LocalDateTime

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
class JobNotAcceptedException(message: String) : IllegalStateException(message)

data class JobAccepted(
	val jobId: Long,
	val jobName: String,
	val status: String,
	val poll: String,
)

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

data class JobProgress(
	val jobId: Long,
	val jobName: String,
	val status: String,
	val running: Boolean,
	val startedAt: String?,
	val endedAt: String?,
	val elapsedMs: Long?,
	val steps: List<StepProgress>,

	val summary: Map<String, String>,
	val failure: String?,
)

@Service
class IndexJobService(
	private val jobOperator: JobOperator,
	private val jobRepository: JobRepository,
	jobs: List<Job>,
) {
	private val byName = jobs.associateBy { it.name }

	init {
		log.info("등록된 색인 job: {}", byName.keys.sorted())
	}

	fun launch(jobName: String, trigger: String = IndexJobs.TRIGGER_MANUAL): JobAccepted {
		val job = byName[jobName] ?: error("그런 색인 job 이 없습니다: $jobName")

		val parameters = JobParametersBuilder()
			.addLocalDateTime(IndexJobs.PARAM_REQUESTED_AT, LocalDateTime.now())
			.addString(IndexJobs.PARAM_TRIGGER, trigger, false)
			.toJobParameters()

		val execution = jobOperator.start(job, parameters)

		if (execution.status.isUnsuccessful) {
			val reason = execution.allFailureExceptions.firstOrNull()?.message
				?: execution.exitStatus.exitDescription.lineSequence().firstOrNull()?.ifEmpty { null }
				?: "실행 큐가 가득 찼습니다 (동시 1 + 대기 8)"
			log.warn("색인 job 접수 거부 — {} #{}: {}", jobName, execution.id, reason)
			throw JobNotAcceptedException("색인 job 을 접수하지 못했습니다 ($jobName): $reason")
		}

		log.info("색인 job 접수 — {} #{} (트리거: {})", jobName, execution.id, trigger)

		return JobAccepted(
			jobId = execution.id,
			jobName = jobName,
			status = execution.status.name,
			poll = "/admin/jobs/${execution.id}",
		)
	}

	fun progress(jobId: Long): JobProgress? {
		val execution = try {
			jobRepository.getJobExecution(jobId) ?: return null
		} catch (_: EmptyResultDataAccessException) {
			return null
		}

		return JobProgress(
			jobId = execution.id,
			jobName = execution.jobInstance.jobName,
			status = execution.status.name,
			running = execution.status.isRunning,
			startedAt = execution.startTime?.toString(),
			endedAt = execution.endTime?.toString(),
			elapsedMs = elapsedMillis(execution.startTime, execution.endTime),

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

	fun recent(jobName: String, limit: Int): List<JobProgress> =
		try {
			jobRepository.getJobInstances(jobName, 0, limit)
				.flatMap { jobRepository.getJobExecutions(it) }
				.sortedByDescending { it.createTime }
				.take(limit)
				.mapNotNull { progress(it.id) }
		} catch (_: EmptyResultDataAccessException) {
			emptyList()
		}

	fun isRegistered(jobName: String): Boolean = jobName in byName

	fun runningCount(jobName: String): Int =
		jobRepository.findRunningJobExecutions(jobName).size

	private fun summaryOf(execution: org.springframework.batch.core.job.JobExecution): Map<String, String> {
		val ctx = execution.executionContext
		return SUMMARY_KEYS
			.mapNotNull { key -> ctx.get(key)?.toString()?.ifEmpty { null }?.let { key to it } }
			.toMap()
	}

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
