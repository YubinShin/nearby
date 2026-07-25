package dev.yubin.search.indexer.batch

import dev.yubin.search.indexer.index.IndexAdminService
import dev.yubin.search.indexer.vector.QdrantIndexStore
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.listener.JobExecutionListener

/**
 * 전체 재색인이 **스왑 전에** 실패하면, 만들어만 놓고 아무도 안 쓰는 인덱스가 남는다. 그걸 지운다.
 *
 * ### 왜 try/catch 가 아니라 리스너인가
 * 전에는 적재를 `try { ... } catch (e) { admin.deleteIndices(...); throw e }` 로 감싸고 있었다.
 * 그 방식은 **적재 도중의 실패만** 잡는다. step 을 쪼개고 나니 실패할 수 있는 자리가 늘었다 —
 * 승격 step 의 alias 스왑, 도장 쓰기, 체크포인트 갱신. 리스너는 job 이 어디서 어떻게 끝나든
 * 마지막에 한 번 불리므로 **모든 실패 경로를 한 곳에서** 처리한다.
 *
 * 프로세스가 그냥 죽는 경우(OOM·SIGKILL)는 이 리스너도 못 잡는다. 그건 다음 재색인의 prepare
 * step 이 부르는 `IndexAdminService.sweepOrphansAbove` 나 `POST /admin/cleanup` 이 치운다 —
 * 그래서 정리 규칙이 두 겹으로 있다.
 *
 * ### 승격 뒤에는 절대 지우지 않는다
 * step 을 쪼개면서 **실패할 수 있는 자리가 alias 스왑 뒤로도 늘었다** — 정리(reconcile), 버전
 * 도장, 체크포인트 갱신. 그 자리에서 실패하면 job 은 FAILED 지만 인덱스는 **이미 서빙 중**이다.
 * 그걸 지우면 alias 가 함께 사라져 검색이 전면 장애가 난다(`index_not_found_exception`).
 *
 * 그래서 승격 step 이 스왑 직후 [IndexJobs.Ctx.PROMOTED] 를 찍고, 여기서는 그게 있으면
 * 손을 뗀다. **정리 실패보다 장애가 훨씬 비싸므로, 애매하면 남기는 쪽으로 기운다** — 남은
 * 인덱스는 다음 재색인이나 수동 정리가 치우지만, 지워진 라이브 인덱스는 사람이 재색인을
 * 돌리기 전까지 복구되지 않는다.
 *
 * ### 왜 alias 이름은 반드시 걸러내나
 * 증분 job 도 같은 컨텍스트 키에 대상 이름을 넣는데, 그때 들어가는 값은 **alias** 다.
 * 이 리스너를 증분 job 에 실수로 붙이면 서빙 중인 인덱스를 지우게 된다. 붙이지 않는 것으로도
 * 충분하지만, 실수의 대가가 '검색 전면 장애'라서 값을 한 번 더 확인한다.
 * (`deleteIndices` 는 alias 를 받으면 그 뒤의 실제 인덱스를 지운다 — 조용히 치명적이다.)
 */
class OrphanIndexCleanupListener(
	private val admin: IndexAdminService,
	private vararg val protectedAliases: String,
) : JobExecutionListener {

	override fun afterJob(jobExecution: JobExecution) {
		if (!jobExecution.status.isUnsuccessful) return

		val ctx = jobExecution.executionContext

		// 스왑이 끝났으면 이건 고아가 아니라 **지금 서빙 중인 인덱스**다. 지우면 검색이 죽는다.
		if (ctx.getString(IndexJobs.Ctx.PROMOTED, "").isNotEmpty()) {
			log.warn("재색인이 승격 뒤에 실패했다 — 인덱스는 서빙 중이므로 정리하지 않는다 (수동 확인 필요)")
			return
		}

		val orphans = listOf(IndexJobs.Ctx.SEARCH_INDEX, IndexJobs.Ctx.SUGGEST_INDEX)
			.mapNotNull { ctx.getString(it, "").ifEmpty { null } }
			.filterNot { it in protectedAliases }
			.toSet()

		if (orphans.isEmpty()) return

		// 정리 자체가 실패해도 job 의 실패 이유를 덮어쓰면 안 된다 — 원인은 그쪽이 더 중요하다.
		runCatching { admin.deleteIndices(orphans) }
			.onSuccess { log.warn("재색인 실패 — 고아 인덱스 {}개 정리함 {}", orphans.size, orphans.sorted()) }
			.onFailure { log.error("재색인 실패 + 고아 인덱스 정리도 실패 {} — 수동 정리 필요", orphans.sorted(), it) }
	}

	private companion object {
		val log = LoggerFactory.getLogger(OrphanIndexCleanupListener::class.java)
	}
}

/**
 * 벡터 쪽 같은 역할. Qdrant 는 컬렉션이라 지우는 대상이 하나다.
 * 이유와 주의점은 [OrphanIndexCleanupListener] 와 같다.
 */
class OrphanCollectionCleanupListener(
	private val qdrant: QdrantIndexStore,
	private val protectedAlias: String,
) : JobExecutionListener {

	override fun afterJob(jobExecution: JobExecution) {
		if (!jobExecution.status.isUnsuccessful) return

		val ctx = jobExecution.executionContext

		// 스왑 뒤 실패면 이건 서빙 중인 컬렉션이다 — 지우면 벡터 검색이 죽는다.
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
