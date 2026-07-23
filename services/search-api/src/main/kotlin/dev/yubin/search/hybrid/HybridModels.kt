package dev.yubin.search.hybrid

import com.fasterxml.jackson.annotation.JsonUnwrapped
import dev.yubin.search.query.PlaceHit

/**
 * 결합 결과 한 건.
 *
 * [place] 는 `/v1/search` 의 결과와 **같은 모양 그대로** 펼쳐진다(`@JsonUnwrapped`). 클라이언트가
 * 엔드포인트만 바꿔도 파싱 코드를 안 고쳐도 되고, 추가 필드는 순수하게 덧붙는 형태가 된다.
 *
 * `place.score` 에는 **RRF 점수**가 들어간다. 이 응답의 정렬 근거가 그것이기 때문이다.
 * 각 엔진이 매긴 원래 점수는 [scores] 에 따로 남긴다 — 두 값의 스케일이 얼마나 다른지
 * (BM25 는 두 자리, 코사인은 0~1) 응답만 봐도 드러나게 하려는 것이다. ADR 0003 이 RRF 를
 * 고른 이유가 바로 그 차이라, 근거를 문서 밖에서도 확인할 수 있어야 한다.
 */
data class HybridHit(
	@get:JsonUnwrapped val place: PlaceHit,
	/** 채널별 등수 (1-base). 그 채널이 못 찾았으면 키 자체가 없다. */
	val ranks: Map<String, Int>,
	/** 채널별 원점수. keyword=BM25, vector=코사인 유사도. */
	val scores: Map<String, Double>,
)

/**
 * 채널 하나가 어떻게 됐는지. **실패해도 응답이 나가기 때문에**([failed]) 어느 채널이 빠진
 * 결과인지 호출 측이 알 수 있어야 한다. 조용히 반쪽 결과를 주는 게 제일 나쁘다.
 */
data class ChannelReport(
	val name: String,
	val candidates: Int,
	val tookMs: Long,
	val failed: Boolean = false,
)

data class HybridResponse(
	val query: String,
	/**
	 * 결합 후보 중 유니크 문서 수. **코퍼스 전체 매칭 수가 아니다** —
	 * 각 채널에서 `psp.hybrid.candidates` 개씩만 가져와 합친 결과의 크기다.
	 */
	val total: Long,
	val page: Int,
	val size: Int,
	val tookMs: Long,
	/** 채널 중 하나라도 실패해 반쪽으로 답한 경우 true. */
	val degraded: Boolean = false,
	val channels: List<ChannelReport> = emptyList(),
	val hits: List<HybridHit> = emptyList(),
)
