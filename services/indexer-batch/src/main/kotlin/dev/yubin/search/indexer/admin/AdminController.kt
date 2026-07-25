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

/** 수동 버전 정리 결과. */
data class CleanupResult(val kept: Int, val removed: List<String>)

/**
 * 운영용 색인 트리거·조회.
 *
 * 이 컨트롤러는 `indexer-batch` 아티팩트에만 있다 — 검색 API 는 `/admin` 을 아예 갖고 있지
 * 않다. 전에는 `psp.role.indexer=false` 플래그로 빈을 껐는데, 그건 "코드는 있는데 안 뜬다"라
 * 플래그가 잘못 켜지면 질의 노드에 색인 API 가 열린다. 지금은 클래스가 없다
 * (아키텍처 크리틱 #5 · #9, ADR 0011).
 *
 * ### 202 로 바뀐 이유 (ADR 0013)
 * 전에는 재색인 엔드포인트가 색인이 끝날 때까지 응답하지 않고 결과 요약을 200 으로 돌려줬다.
 * 8분(벡터, kind 32분)짜리 작업을 HTTP 요청 수명에 매달아 둔 셈이라, `curl` 을 끊으면 색인도
 * 죽었다. 지금은 **접수하면 즉시 202 + jobId** 를 주고 진행은 [jobProgress] 로 따로 본다.
 *
 * ```
 * curl -XPOST localhost:8081/admin/reindex          # → 202 {"jobId":12, "poll":"/admin/jobs/12"}
 * curl localhost:8081/admin/jobs/12                 # → 진행/결과
 * ```
 *
 * 정리(`/cleanup`)만 예외로 동기다 — alias 를 보고 옛 버전을 지우는 것뿐이라 밀리초 단위로 끝나고,
 * job 이력을 남길 가치가 없다. **오래 걸리는 것만 job 으로 만든다.**
 */
@RestController
@RequestMapping("/admin")
class AdminController(
	private val jobs: IndexJobService,
	private val admin: IndexAdminService,
	@Value("\${psp.index.search-alias}") private val searchAlias: String,
	@Value("\${psp.index.suggest-alias}") private val suggestAlias: String,
	@Value("\${psp.index.keep-versions:2}") private val keepVersions: Int,
) {

	/** 무중단 전체 재색인: 새 버전 생성 → 적재 → alias 스왑 → 옛 버전 삭제. */
	@PostMapping("/reindex")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun reindex(): JobAccepted = jobs.launch(IndexJobs.KEYWORD_REBUILD)

	/** 증분 재색인: watermark 이후 바뀐 것만 현재 alias 인덱스에 upsert. */
	@PostMapping("/reindex/incremental")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun incremental(): JobAccepted = jobs.launch(IndexJobs.KEYWORD_INCREMENTAL)

	/**
	 * 색인 job 하나의 진행 상황·결과.
	 *
	 * 끝난 job 도 답한다 — 이력이 Postgres 의 `BATCH_*` 테이블에 남아 있어서, 색인기를 재시작한
	 * 뒤에도 어제 실행이 몇 건을 색인했는지 조회할 수 있다.
	 */
	@GetMapping("/jobs/{jobId}")
	fun jobProgress(@PathVariable jobId: Long): JobProgress =
		jobs.progress(jobId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "그런 색인 실행이 없습니다: $jobId")

	/** 특정 job 의 최근 실행 이력. */
	@GetMapping("/jobs")
	fun recentJobs(
		@RequestParam(defaultValue = IndexJobs.KEYWORD_REBUILD) name: String,
		@RequestParam(defaultValue = "10") limit: Int,
	): List<JobProgress> = jobs.recent(name, limit.coerceIn(1, MAX_HISTORY))

	/**
	 * 수동 버전 정리: 재색인 없이 현재 포함 keep-versions 개만 남기고 옛 버전·고아 삭제.
	 * 전체 재색인이 끝에서 하는 것과 같은 규칙(reconcile)이다.
	 */
	@PostMapping("/cleanup")
	fun cleanup(): CleanupResult {
		val removed = admin.reconcile(searchAlias, keepVersions) + admin.reconcile(suggestAlias, keepVersions)
		return CleanupResult(kept = keepVersions, removed = removed.sorted())
	}

	private companion object {
		const val MAX_HISTORY = 50
	}
}
