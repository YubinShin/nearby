package dev.yubin.search.core.index

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 버전 인덱스/컬렉션 이름 규칙: **`{alias}_{yyyyMMddHHmmss}` (UTC)**.
 *
 * 정수 증번(`_v1`, `_v2`) 대신 타임스탬프를 쓴다. 이유는 **운영**이다 — `place_search_v7` 은
 * 언제 만든 빌드인지 0의 정보지만, `place_search_20260725_...`(→ 2026-07-25 14:30:22)은 이름만
 * 봐도 "오늘 거"가 보인다. 롤백·장애 판단에서 이게 결정적이다(유빈 회사도 날짜로 alias 를 걸었다).
 *
 * 고정폭 14자리라 **문자열 정렬 = 시간 정렬**이다. 그래서 reconcile 의 핵심 불변식
 * — "현재 alias 가 가리키는 것보다 **나중** 이름(=진행 중인 새 빌드)은 절대 안 지운다" —
 * 이 정수 비교 없이 문자열 비교만으로 그대로 성립한다.
 *
 * 색인기가 ES·Qdrant 양쪽에서 **같은 규칙**을 써야 하므로 search-core 에 둔다 (ADR 0011).
 * (질의기는 alias 로만 질의하므로 이 토큰을 파싱하지 않는다 — 여기 의존하지 않는다.)
 *
 * 주의: 초 단위 해상도라, 같은 alias 를 **같은 초에 두 번** 재색인하면 이름이 겹친다. 사람이
 * 그 속도로 두 번 누르긴 어렵고, 겹치면 인덱스 생성이 실패(안전한 실패 — 데이터는 안 상함)한다.
 */
object IndexVersion {

	private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

	/** 지금 시각으로 새 버전 이름을 만든다. */
	fun newName(alias: String): String = "${alias}_${FORMAT.format(Instant.now())}"

	/** 이름에서 14자리 타임스탬프 토큰을 뽑는다. 규칙에 안 맞으면(옛 `_v1` 등) null. */
	fun tokenOf(alias: String, name: String): String? =
		Regex("^${Regex.escape(alias)}_(\\d{14})$").find(name)?.groupValues?.get(1)
}
