package dev.yubin.search.query

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 질의 로그 — 검색 플랫폼에서 **가장 먼저 쌓아야 하는 자산**.
 *
 * 지금 당장 두 가지 숙제가 이 로그 없이는 풀리지 않는다.
 *  1. **사전 확보** — 결과 0건 질의는 미등록 어휘의 직접 증거다 (ADR 0008 의 두 번째 사전 원천).
 *     원천 데이터 기반 사전은 *데이터에 있는 말*만 담는다. *사용자가 치는 말*은 여기서만 나온다.
 *  2. **랭킹 근거** — 지금 필드 가중치(name^5 …)는 도메인 직관일 뿐 근거 데이터가 없다
 *     (아키텍처 크리틱 #11). 질의–클릭 쌍이 쌓여야 nDCG 로 평가·튜닝할 수 있다.
 *
 * 형식은 **한 줄 JSON**이다. 사람이 읽기보다 기계가 집계하기 위한 로그이므로,
 * 로그 설정(logback)에서 별도 파일로 떼어 내면 그대로 분석 입력이 된다.
 * → 소비: `scripts/mine_query_log.py`
 *
 * 개인정보: 질의문 외에 식별자를 남기지 않는다. 세션 흐름이 필요해지면 ADR 0004 의
 * 쿠키리스 임시 UUID 를 붙이되, 재방문 추적으로 넘어가지 않는다.
 */
@Component
class QueryLog {

	/**
	 * 검색 한 건. `zero`(결과 없음)와 `relaxed`(조건을 풀어 재질의)가 사전 후보의 신호다.
	 * 특히 **relaxed 인데도 걸린 질의**는 "글자는 아는데 제대로 못 쪼갠" 경우일 확률이 높다.
	 *
	 * `channel` 을 반드시 남긴다. 벡터 채널(5단계)의 0건은 **형태소 분석과 아무 상관이 없어서**,
	 * 섞이면 사전 채굴이 엉뚱한 후보를 뽑는다 — 소비자(`mine_query_log.py`)는 키워드만 봐야 한다.
	 */
	fun search(q: String, total: Long, relaxed: Boolean, tookMs: Long, channel: String = KEYWORD) {
		if (q.isBlank()) return
		log.info(
			"""{"type":"search","channel":"{}","q":"{}","total":{},"zero":{},"relaxed":{},"took_ms":{}}""",
			channel, escape(q), total, total == 0L, relaxed, tookMs,
		)
	}

	fun suggest(q: String, hits: Int, tookMs: Long) {
		if (q.isBlank()) return
		log.info(
			"""{"type":"suggest","q":"{}","hits":{},"zero":{},"took_ms":{}}""",
			escape(q), hits, hits == 0, tookMs,
		)
	}

	/** 질의문이 JSON 을 깨뜨리지 않게 최소한만 이스케이프한다. */
	private fun escape(q: String): String =
		q.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

	companion object {
		const val KEYWORD = "keyword"

		/** 로거 이름을 고정해 logback 에서 이 로거만 별도 파일로 뗄 수 있게 한다. */
		private val log = LoggerFactory.getLogger("psp.querylog")
	}
}
