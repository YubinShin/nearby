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

/**
 * 벡터 색인 트리거. 키워드 색인([AdminController])과 **따로** 부른다.
 * 임베딩 추론이 훨씬 느려 두 파이프라인의 주기가 다르기 때문
 * (`dev.yubin.search.indexer.batch.VectorIndexJobConfig` 주석 참고).
 *
 * 진행 조회는 키워드와 **같은 곳**을 쓴다 — `GET /admin/jobs/{jobId}`. job 이 무엇이든 이력은 한
 * 저장소(`BATCH_*`)에 남으므로 조회 창구를 둘로 만들 이유가 없다.
 */
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
	@Value("\${psp.index.keep-versions:2}") private val keepVersions: Int,
) {

	/** 무중단 전체 재색인: 새 컬렉션 생성 → 임베딩 적재 → alias 스왑 → 옛 컬렉션 삭제. */
	@PostMapping("/reindex")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun reindex(): JobAccepted = jobs.launch(IndexJobs.VECTOR_REBUILD)

	/** 증분: 벡터 체크포인트 이후 바뀐 것만 다시 임베딩해 현재 컬렉션에 upsert. */
	@PostMapping("/reindex/incremental")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun incremental(): JobAccepted = jobs.launch(IndexJobs.VECTOR_INCREMENTAL)

	/**
	 * 수동 버전 정리: 재색인 없이 현재 포함 keep-versions 개만 남기고 옛 컬렉션·고아 삭제.
	 * 정리가 두 종류인 이유는 키워드 쪽([AdminController.cleanup])과 같다.
	 */
	@PostMapping("/cleanup")
	fun cleanup(): CleanupResult {
		val orphans = qdrant.sweepOrphansAbove(alias)
		val old = qdrant.reconcile(alias, keepVersions)
		return CleanupResult(kept = keepVersions, removed = (orphans + old).sorted())
	}
}
