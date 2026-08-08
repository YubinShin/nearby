package dev.yubin.search.indexer.admin

import dev.yubin.search.indexer.batch.IndexJobs
import dev.yubin.search.indexer.batch.IndexJobService
import dev.yubin.search.indexer.batch.JobAccepted
import dev.yubin.search.indexer.batch.JobProgress
import dev.yubin.search.indexer.index.IndexAdminService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class CleanupResult(val kept: Int, val removed: List<String>)

internal fun rejectWhileRebuilding(jobs: IndexJobService, jobName: String) {
	val running = jobs.runningCount(jobName)
	if (running > 0) {
		throw ResponseStatusException(
			HttpStatus.CONFLICT,
			"$jobName is running — cleanup would delete the index it is loading into. retry once it finishes.",
		)
	}
}

@RestController
@RequestMapping("/admin")
class AdminController(
	private val jobs: IndexJobService,
	private val admin: IndexAdminService,
	@Value("\${psp.index.search-alias}") private val searchAlias: String,
	@Value("\${psp.index.suggest-alias}") private val suggestAlias: String,
	@Value("\${psp.index.keep-versions}") private val keepVersions: Int,
) {
	@PostMapping("/reindex")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun reindex(): JobAccepted = jobs.launch(IndexJobs.KEYWORD_REBUILD)

	@PostMapping("/reindex/incremental")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun incremental(): JobAccepted = jobs.launch(IndexJobs.KEYWORD_INCREMENTAL)

	@GetMapping("/jobs/{jobId}")
	fun jobProgress(@PathVariable jobId: Long): JobProgress =
		jobs.progress(jobId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such index run: $jobId")

	@GetMapping("/jobs")
	fun recentJobs(
		@RequestParam(defaultValue = IndexJobs.KEYWORD_REBUILD) name: String,
		@RequestParam(defaultValue = "10") limit: Int,
	): List<JobProgress> = jobs.recent(name, limit.coerceIn(1, MAX_HISTORY))

	@PostMapping("/cleanup")
	fun cleanup(): CleanupResult {
		rejectWhileRebuilding(jobs, IndexJobs.KEYWORD_REBUILD)

		val orphans = admin.sweepOrphansAbove(searchAlias) + admin.sweepOrphansAbove(suggestAlias)
		val old = admin.reconcile(searchAlias, keepVersions) + admin.reconcile(suggestAlias, keepVersions)
		return CleanupResult(kept = keepVersions, removed = (orphans + old).sorted())
	}

	private companion object {
		const val MAX_HISTORY = 50
	}
}
