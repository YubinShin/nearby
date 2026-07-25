package dev.yubin.search.indexer

import dev.yubin.search.indexer.batch.IndexJobs
import dev.yubin.search.indexer.batch.IndexJobService
import dev.yubin.search.indexer.batch.JobNotAcceptedException
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
 * - **tombstone** — 전체 재색인이 곧 청소다.
 *
 * 전체 재색인은 이걸 전부 쓸어낸다. 비용이 실측으로 확인돼 있어서 결정이 감이 아니다:
 * **키워드 15.6초, 벡터 8분 32초 (64,239건).** 벡터가 하루의 0.6% 다 — 드리프트를 고민하는
 * 것보다 매일 다시 만드는 쪽이 싸다.
 *
 * ### 기본값이 꺼져 있는 이유
 * 앱이 뜨자마자 원천을 훑기 시작하면 로컬 시연·실측이 방해받는다. 프로덕션에서 켜는 스위치로
 * 두고, 여기 적힌 cron 값이 곧 "프로덕션이면 이렇게 돈다"의 기록이다.
 *
 * ### 겹침 방지가 여기서 사라진 이유 (ADR 0013)
 * 전에는 이 클래스가 색인을 **직접 실행**했고, "스프링 기본 스케줄러는 스레드 하나라 증분과
 * 전체가 겹치지 않고 줄을 선다"는 사실에 안전성을 걸고 있었다. 우연히 맞는 구조였다 — HTTP 로
 * 재색인을 부르면 그 보장은 없었다.
 *
 * 지금은 스케줄러도 HTTP 도 **같은 job 큐**로 들어간다([BatchConfig] 의 단일 스레드 풀). 그래서
 * 겹침 방지가 스케줄러의 성질이 아니라 **한 곳에 명시된 규칙**이 됐고, 어느 경로로 들어와도
 * 같은 인덱스를 동시에 만지지 않는다. 이 클래스가 하는 일은 이제 "시각이 되면 job 을 큐에
 * 넣는다"뿐이다.
 */
@Component
@ConditionalOnProperty(prefix = "psp.index.schedule", name = ["enabled"], havingValue = "true")
class ReindexScheduler(private val jobs: IndexJobService) {

	/** 신선도 담당. 바뀐 것만 따라잡는다. */
	@Scheduled(cron = "\${psp.index.schedule.incremental-cron}")
	fun incremental() {
		enqueue(IndexJobs.KEYWORD_INCREMENTAL)
		enqueue(IndexJobs.VECTOR_INCREMENTAL)
	}

	/** 위생 담당. 도장이 못 잡는 어긋남까지 쓸어낸다. */
	@Scheduled(cron = "\${psp.index.schedule.full-cron}")
	fun full() {
		enqueue(IndexJobs.KEYWORD_REBUILD)
		enqueue(IndexJobs.VECTOR_REBUILD)
	}

	/**
	 * 예외를 먹되 **삼키지는 않는다** — 로그로 남기고 다음 주기를 살린다.
	 * 여기서 예외가 밖으로 나가면 스프링 스케줄러가 그 작업을 **영구히 멈춘다.**
	 * 색인 한 번 실패했다고 이후 색인이 통째로 서면 안 된다.
	 *
	 * 이제 여기서 잡히는 건 **접수 실패**뿐이다. 색인 자체의 실패는 job 안에서 일어나고
	 * `BATCH_JOB_EXECUTION` 에 FAILED 로 남는다 — 전에는 이 로그 한 줄이 유일한 기록이었지만,
	 * 지금은 조회할 수 있는 이력이 된다.
	 *
	 * 도장 불일치로 증분이 거부되는 경우도 마찬가지다. 그때는 사람이 전체 재색인을 돌려야 하므로,
	 * 재시도로 뭉개지 않고 실행 이력에 같은 실패가 쌓여 눈에 띄게 둔다.
	 *
	 * ### 심각도를 세 갈래로 나눈다
	 * 전부 ERROR 로 찍으면 **정상 구성이 내는 소음에 알람이 묻힌다.**
	 * - **없는 job** — `psp.vector.enabled=false` 노드에 벡터 job 이 없는 건 지원되는 구성이다.
	 *   부르기 전에 걸러 아무것도 남기지 않는다(디버그만). 전에는 이게 5분마다 ERROR + 스택트레이스로
	 *   찍혀 하루 288줄이 됐고, 멀쩡한 노드에서 에러율 알람이 영구히 울렸다.
	 * - **큐 참** — 배압이지 고장이 아니다. WARN 으로 남기고 다음 주기에 다시 건다.
	 * - **그 밖** — 진짜 예상 밖이므로 ERROR + 스택트레이스.
	 */
	private fun enqueue(jobName: String) {
		// 벡터를 끄고 뜬 노드에는 벡터 job 이 없다 — 조용히 건너뛴다(주석대로 실제로 조용하게).
		if (!jobs.isRegistered(jobName)) {
			log.debug("이 노드에 없는 색인 job 이라 건너뜀 — {}", jobName)
			return
		}

		try {
			val accepted = jobs.launch(jobName, IndexJobs.TRIGGER_SCHEDULE)
			log.info("예약 색인 접수 — {} #{}", accepted.jobName, accepted.jobId)
		} catch (e: JobNotAcceptedException) {
			log.warn("예약 색인 접수 보류 ({}) — 다음 주기에 다시 시도합니다: {}", jobName, e.message)
		} catch (e: Exception) {
			log.error("예약 색인 접수 실패 ({}) — 다음 주기에 다시 시도합니다: {}", jobName, e.message, e)
		}
	}

	private companion object {
		val log = LoggerFactory.getLogger(ReindexScheduler::class.java)
	}
}
