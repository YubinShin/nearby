package dev.yubin.search.indexer.admin

import dev.yubin.search.indexer.batch.IndexJobs
import dev.yubin.search.indexer.batch.IndexJobService
import dev.yubin.search.indexer.batch.JobAccepted
import dev.yubin.search.indexer.vector.QdrantIndexStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/vector")
@ConditionalOnProperty(
	name = ["psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class VectorAdminController(
	private val jobs: IndexJobService,
	private val qdrant: QdrantIndexStore,
	@Value("\${psp.vector.alias}") private val alias: String,
	@Value("\${psp.index.keep-versions}") private val keepVersions: Int,
) {
	@PostMapping("/reindex")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun reindex(): JobAccepted = jobs.launch(IndexJobs.VECTOR_REBUILD)

	@PostMapping("/reindex/incremental")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun incremental(): JobAccepted = jobs.launch(IndexJobs.VECTOR_INCREMENTAL)

	@PostMapping("/cleanup")
	fun cleanup(): CleanupResult {
		rejectWhileRebuilding(jobs, IndexJobs.VECTOR_REBUILD)

		val orphans = qdrant.sweepOrphansAbove(alias)
		val old = qdrant.reconcile(alias, keepVersions)
		return CleanupResult(kept = keepVersions, removed = (orphans + old).sorted())
	}
}
