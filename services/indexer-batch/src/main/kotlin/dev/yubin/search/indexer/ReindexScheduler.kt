package dev.yubin.search.indexer

import dev.yubin.search.indexer.index.ReindexService
import dev.yubin.search.indexer.vector.VectorIndexService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 색인 주기 — **세 층으로 나눠 생각한다** (ADR 0011).
 *
 * | 무엇 | 주기 | 무엇이 정하나 |
 * |---|---|---|
 * | 증분 | 분 단위 | 신선도 요구 ↔ 변경량. 장소 데이터는 느리게 바뀐다 (ADR 0001) |
 * | 전체 재색인 | 하루 1회 | 전체 재색인 비용 ↔ 드리프트 상한선 |
 * | 모델·스키마 변경 | **주기가 아니라 이벤트** | 도장 대조가 증분을 거부해 즉시 전체 재색인을 강제 |
 *
 * ### 왜 매일 전체 재색인인가 — 도장이 못 잡는 것들
 * 버전 도장(`IndexMeta`)은 **우리가 담기로 한 것**만 지킨다. 담지 않은 어긋남은 그대로 쌓인다.
 * - **형태소 사전 변경** — 질의 로그 채굴로 계속 자란다(ADR 0008). 사전을 고쳐도 이미 색인된
 *   문서의 토큰은 그대로다. 증분은 **바뀐 행만** 건드리므로 나머지는 옛 분석 결과로 남는다.
 * - **원천이 `updated_at` 을 안 올리고 값만 고친 경우** — watermark 기반 증분은 이걸 영영 못 본다.
 * - **tombstone** — 전체 재색인이 곧 청소다 (`ReindexService` 주석).
 *
 * 전체 재색인은 이걸 전부 쓸어낸다. 비용이 실측으로 확인돼 있어서 결정이 감이 아니다:
 * **키워드 17초, 벡터 8분 33초 (64,239건).** 벡터가 하루의 0.6% 다 — 드리프트를 고민하는
 * 것보다 매일 다시 만드는 쪽이 싸다.
 *
 * ### 기본값이 꺼져 있는 이유
 * 앱이 뜨자마자 원천을 훑기 시작하면 로컬 시연·실측이 방해받는다. 프로덕션에서 켜는 스위치로
 * 두고, 여기 적힌 cron 값이 곧 "프로덕션이면 이렇게 돈다"의 기록이다.
 *
 * 스프링 기본 스케줄러는 스레드 하나라 증분과 전체가 **겹치지 않고 줄을 선다.** 전체 재색인이
 * 8분 걸리는 동안 증분은 밀린다 — 의도한 것이다. 둘이 같은 인덱스를 동시에 만지면 안 된다.
 */
@Component
@ConditionalOnProperty(prefix = "psp.index.schedule", name = ["enabled"], havingValue = "true")
class ReindexScheduler(
	private val reindex: ReindexService,
	private val vectors: VectorIndexService,
) {

	/** 신선도 담당. 바뀐 것만 따라잡는다. */
	@Scheduled(cron = "\${psp.index.schedule.incremental-cron}")
	fun incremental() {
		run("증분 색인") {
			val keyword = reindex.incremental()
			val vector = vectors.incremental()
			"키워드 ${keyword.matched}건, 벡터 ${vector.matched}건"
		}
	}

	/** 위생 담당. 도장이 못 잡는 어긋남까지 쓸어낸다. */
	@Scheduled(cron = "\${psp.index.schedule.full-cron}")
	fun full() {
		run("전체 재색인") {
			val keyword = reindex.rebuildAndSwap()
			val vector = vectors.rebuildAndSwap()
			"키워드 ${keyword.read}건, 벡터 ${vector.upserted}건 (${vector.elapsedMs}ms)"
		}
	}

	/**
	 * 예외를 먹되 **삼키지는 않는다** — 로그로 남기고 다음 주기를 살린다.
	 * 여기서 예외가 밖으로 나가면 스프링 스케줄러가 그 작업을 **영구히 멈춘다.**
	 * 색인 한 번 실패했다고 이후 색인이 통째로 서면 안 된다.
	 *
	 * 도장 불일치로 증분이 거부되는 경우도 여기로 온다. 그때는 사람이 전체 재색인을 돌려야
	 * 하므로, 재시도로 뭉개지 않고 매 주기 같은 에러를 남겨 눈에 띄게 둔다.
	 */
	private fun run(label: String, block: suspend () -> String) {
		val startedAt = System.nanoTime()
		try {
			val summary = runBlocking { block() }
			log.info("{} 완료 — {} ({}ms)", label, summary, (System.nanoTime() - startedAt) / 1_000_000)
		} catch (e: Exception) {
			log.error("{} 실패 — 다음 주기에 다시 시도합니다: {}", label, e.message, e)
		}
	}

	private companion object {
		val log = LoggerFactory.getLogger(ReindexScheduler::class.java)
	}
}
