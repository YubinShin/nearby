package dev.yubin.search.indexer.batch

import dev.yubin.search.indexer.index.IndexAdminService
import dev.yubin.search.indexer.vector.QdrantIndexStore
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.listener.JobExecutionListener

class OrphanIndexCleanupListener(
	private val admin: IndexAdminService,
	private vararg val protectedAliases: String,
) : JobExecutionListener {
	override fun afterJob(jobExecution: JobExecution) {
		if (!jobExecution.status.isUnsuccessful) return

		val ctx = jobExecution.executionContext

		if (ctx.getString(IndexJobs.Ctx.PROMOTED, "").isNotEmpty()) {
			log.warn("재색인이 승격 뒤에 실패했다 — 인덱스는 서빙 중이므로 정리하지 않는다 (수동 확인 필요)")
			return
		}

		val orphans = listOf(IndexJobs.Ctx.SEARCH_INDEX, IndexJobs.Ctx.SUGGEST_INDEX)
			.mapNotNull { ctx.getString(it, "").ifEmpty { null } }
			.filterNot { it in protectedAliases }
			.toSet()

		if (orphans.isEmpty()) return

		runCatching { admin.deleteIndices(orphans) }
			.onSuccess { log.warn("재색인 실패 — 고아 인덱스 {}개 정리함 {}", orphans.size, orphans.sorted()) }
			.onFailure { log.error("재색인 실패 + 고아 인덱스 정리도 실패 {} — 수동 정리 필요", orphans.sorted(), it) }
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
			log.warn("벡터 재색인이 승격 뒤에 실패했다 — 컬렉션은 서빙 중이므로 정리하지 않는다 (수동 확인 필요)")
			return
		}

		val orphan = ctx.getString(IndexJobs.Ctx.COLLECTION, "")
			.ifEmpty { null }
			?.takeIf { it != protectedAlias }
			?: return

		runCatching { qdrant.deleteCollections(setOf(orphan)) }
			.onSuccess { log.warn("벡터 재색인 실패 — 고아 컬렉션 정리함 {}", orphan) }
			.onFailure { log.error("벡터 재색인 실패 + 고아 컬렉션 {} 정리도 실패 — 수동 정리 필요", orphan, it) }
	}

	private companion object {
		val log = LoggerFactory.getLogger(OrphanCollectionCleanupListener::class.java)
	}
}
