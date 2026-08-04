package dev.yubin.search.indexer.batch

import dev.yubin.search.indexer.index.IndexAdminService
import dev.yubin.search.indexer.vector.QdrantIndexStore
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.listener.JobExecutionListener

private fun JobExecution.completed(step: String): Boolean =
	stepExecutions.any { it.stepName == step && it.status == BatchStatus.COMPLETED }

class OrphanIndexCleanupListener(
	private val admin: IndexAdminService,
	private vararg val protectedAliases: String,
) : JobExecutionListener {
	override fun afterJob(jobExecution: JobExecution) {
		if (!jobExecution.status.isUnsuccessful) return

		val ctx = jobExecution.executionContext

		if (ctx.getString(IndexJobs.Ctx.PROMOTED, "").isNotEmpty()) {
			log.warn("reindex failed after promotion — the indices are serving, leaving them in place (manual check needed)")
			return
		}

		val candidates = listOf(IndexJobs.Ctx.SEARCH_INDEX, IndexJobs.Ctx.SUGGEST_INDEX)
			.mapNotNull { ctx.getString(it, "").ifEmpty { null } }
			.filterNot { it in protectedAliases }
			.toSet()

		if (candidates.isEmpty()) return

		if (jobExecution.completed(IndexJobs.STEP_KEYWORD_LOAD)) {
			log.warn(
				"promotion failed after loading finished — keeping the loaded indices {}. " +
					"move the alias manually or run the reindex again to clean them up",
				candidates.sorted(),
			)
			return
		}

		val serving = try {
			protectedAliases.flatMap { admin.indicesBehind(it) }.toSet()
		} catch (e: Exception) {
			log.error("alias lookup failed — cannot tell what is serving, leaving {} in place", candidates.sorted(), e)
			return
		}

		val serviced = candidates intersect serving
		if (serviced.isNotEmpty()) {
			log.warn("serving behind an alias, excluded from cleanup {}", serviced.sorted())
		}

		val orphans = candidates - serving
		if (orphans.isEmpty()) return

		runCatching { admin.deleteIndices(orphans) }
			.onSuccess { log.warn("reindex failed — swept {} orphan indices {}", orphans.size, orphans.sorted()) }
			.onFailure { log.error("reindex failed and orphan index cleanup failed too {} — manual cleanup needed", orphans.sorted(), it) }
	}

	private companion object {
		val log = LoggerFactory.getLogger(OrphanIndexCleanupListener::class.java)
	}
}

class OrphanCollectionCleanupListener(
	private val qdrant: QdrantIndexStore,
	private val protectedAlias: String,
) : JobExecutionListener {
	override fun afterJob(jobExecution: JobExecution) {
		if (!jobExecution.status.isUnsuccessful) return

		val ctx = jobExecution.executionContext

		if (ctx.getString(IndexJobs.Ctx.PROMOTED, "").isNotEmpty()) {
			log.warn("vector reindex failed after promotion — the collection is serving, leaving it in place (manual check needed)")
			return
		}

		val orphan = ctx.getString(IndexJobs.Ctx.COLLECTION, "")
			.ifEmpty { null }
			?.takeIf { it != protectedAlias }
			?: return

		if (jobExecution.completed(IndexJobs.STEP_VECTOR_LOAD)) {
			log.warn(
				"promotion failed after embedding finished — keeping the loaded collection {}. " +
					"move the alias manually or run the reindex again to clean them up",
				orphan,
			)
			return
		}

		val serving = try {
			qdrant.collectionsBehind(protectedAlias)
		} catch (e: Exception) {
			log.error("alias lookup failed — cannot tell what is serving, leaving collection {} in place", orphan, e)
			return
		}

		if (orphan in serving) {
			log.warn("collection {} is serving behind an alias, leaving it in place (manual check needed)", orphan)
			return
		}

		runCatching { qdrant.deleteCollections(setOf(orphan)) }
			.onSuccess { log.warn("vector reindex failed — swept orphan collection {}", orphan) }
			.onFailure { log.error("vector reindex failed and orphan collection {} cleanup failed too — manual cleanup needed", orphan, it) }
	}

	private companion object {
		val log = LoggerFactory.getLogger(OrphanCollectionCleanupListener::class.java)
	}
}
