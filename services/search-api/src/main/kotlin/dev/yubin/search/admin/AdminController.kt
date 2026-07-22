package dev.yubin.search.admin

import dev.yubin.search.index.IncrementalResult
import dev.yubin.search.index.RebuildResult
import dev.yubin.search.index.ReindexService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 운영용 색인 트리거. 지금은 수동 버튼(전체 재색인).
 * ADR 0001 의 '이벤트 트리거 증분'은 이 자리에서 확장된다 (3-4).
 */
@RestController
@RequestMapping("/admin")
class AdminController(private val reindexService: ReindexService) {

	/** 무중단 전체 재색인: 새 버전 생성 → 적재 → alias 스왑 → 옛 버전 삭제. */
	@PostMapping("/reindex")
	suspend fun reindex(): RebuildResult = reindexService.rebuildAndSwap()

	/** 증분 재색인: watermark 이후 바뀐 것만 현재 alias 인덱스에 upsert. */
	@PostMapping("/reindex/incremental")
	suspend fun incremental(): IncrementalResult = reindexService.incremental()
}
