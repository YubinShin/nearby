package dev.yubin.search.indexer.admin

import dev.yubin.search.indexer.index.IncrementalResult
import dev.yubin.search.indexer.index.RebuildResult
import dev.yubin.search.indexer.index.ReindexService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 운영용 색인 트리거. 지금은 수동 버튼(전체 재색인).
 * ADR 0001 의 '이벤트 트리거 증분'은 이 자리에서 확장된다 (3-4).
 *
 * 이 컨트롤러는 `indexer-batch` 아티팩트에만 있다 — 검색 API 는 `/admin` 을 아예 갖고 있지
 * 않다. 전에는 `psp.role.indexer=false` 플래그로 빈을 껐는데, 그건 "코드는 있는데 안 뜬다"라
 * 플래그가 잘못 켜지면 질의 노드에 색인 API 가 열린다. 지금은 클래스가 없다
 * (아키텍처 크리틱 #5 · #9, ADR 0011).
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
