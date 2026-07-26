package dev.yubin.search.indexer

import dev.yubin.search.indexer.batch.IndexJobs
import dev.yubin.search.indexer.batch.IndexJobService
import dev.yubin.search.indexer.batch.JobNotAcceptedException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "psp.index.schedule", name = ["enabled"], havingValue = "true")
class ReindexScheduler(private val jobs: IndexJobService) {
	@Scheduled(cron = "\${psp.index.schedule.incremental-cron}")
	fun incremental() {
		enqueue(IndexJobs.KEYWORD_INCREMENTAL)
		enqueue(IndexJobs.VECTOR_INCREMENTAL)
	}

	@Scheduled(cron = "\${psp.index.schedule.full-cron}")
	fun full() {
		enqueue(IndexJobs.KEYWORD_REBUILD)
		enqueue(IndexJobs.VECTOR_REBUILD)
	}

	private fun enqueue(jobName: String) {
		if (!jobs.isRegistered(jobName)) {
			log.debug("이 노드에 없는 색인 job 이라 건너뜀 — {}", jobName)
			return
		}

		try {
			val accepted = jobs.launch(jobName, IndexJobs.TRIGGER_SCHEDULE)
			log.info("예약 색인 접수 — {} #{}", accepted.jobName, accepted.jobId)
		} catch (e: JobNotAcceptedException) {
			log.warn("예약 색인 접수 보류 ({}) — 다음 주기에 다시 시도합니다: {}", jobName, e.message)
		} catch (e: Exception) {
			log.error("예약 색인 접수 실패 ({}) — 다음 주기에 다시 시도합니다: {}", jobName, e.message, e)
		}
	}

	private companion object {
		val log = LoggerFactory.getLogger(ReindexScheduler::class.java)
	}
}
