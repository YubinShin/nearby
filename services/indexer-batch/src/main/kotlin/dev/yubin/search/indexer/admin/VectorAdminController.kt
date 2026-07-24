package dev.yubin.search.indexer.admin

import dev.yubin.search.indexer.vector.VectorIncrementalResult
import dev.yubin.search.indexer.vector.VectorIndexService
import dev.yubin.search.indexer.vector.VectorRebuildResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 벡터 색인 트리거. 키워드 색인([AdminController])과 **따로** 부른다.
 * 임베딩 추론이 훨씬 느려 두 파이프라인의 주기가 다르기 때문 (VectorIndexService 주석 참고).
 */
@RestController
@RequestMapping("/admin/vector")
@ConditionalOnProperty(
	name = ["psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class VectorAdminController(private val vectorIndex: VectorIndexService) {

	/** 무중단 전체 재색인: 새 컬렉션 생성 → 임베딩 적재 → alias 스왑 → 옛 컬렉션 삭제. */
	@PostMapping("/reindex")
	suspend fun reindex(): VectorRebuildResult = vectorIndex.rebuildAndSwap()

	/** 증분: 벡터 체크포인트 이후 바뀐 것만 다시 임베딩해 현재 컬렉션에 upsert. */
	@PostMapping("/reindex/incremental")
	suspend fun incremental(): VectorIncrementalResult = vectorIndex.incremental()
}
