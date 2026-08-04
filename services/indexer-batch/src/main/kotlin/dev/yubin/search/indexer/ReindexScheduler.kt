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
			log.debug("skipping scheduled job not registered on this node — {}", jobName)
			return
		}

		try {
			val accepted = jobs.launch(jobName, IndexJobs.TRIGGER_SCHEDULE)
			log.info("scheduled index job accepted — {} #{}", accepted.jobName, accepted.jobId)
		} catch (e: JobNotAcceptedException) {
			log.warn("scheduled index job deferred ({}) — retrying next cycle: {}", jobName, e.message)
		} catch (e: Exception) {
			log.error("scheduled index job failed to start ({}) — retrying next cycle: {}", jobName, e.message, e)
		}
	}

	private companion object {
		val log = LoggerFactory.getLogger(ReindexScheduler::class.java)
	}
}
