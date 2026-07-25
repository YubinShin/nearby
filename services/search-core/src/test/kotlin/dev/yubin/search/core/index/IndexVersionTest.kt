package dev.yubin.search.core.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 버전 이름 규칙 `{alias}_{yyyyMMddHHmmss}` 의 계약을 못박는다.
 *
 * 핵심은 reconcile 의 안전 불변식이다 — "현재보다 **나중** 이름은 진행 중인 빌드일 수 있어
 * 안 지운다". 고정폭 14자리라 **문자열 비교 = 시간 비교**여야 그 판단이 성립한다.
 */
class IndexVersionTest {

	@Test
	fun `newName 은 alias_14자리 형식이고 tokenOf 로 되읽힌다`() {
		val name = IndexVersion.newName("place_search")
		assertTrue(Regex("^place_search_\\d{14}$").matches(name), "형식이 어긋남: $name")
		assertEquals(14, IndexVersion.tokenOf("place_search", name)?.length)
	}

	@Test
	fun `tokenOf 는 규칙에 맞는 이름의 타임스탬프만 뽑는다`() {
		assertEquals("20260725143022", IndexVersion.tokenOf("place_search", "place_search_20260725143022"))
		// 옛 정수 스킴은 관리 대상이 아니다 → null (그래서 reconcile 이 옛 _v1 을 건드리지 않는다)
		assertNull(IndexVersion.tokenOf("place_search", "place_search_v1"))
		// 자리수가 안 맞으면 아니다
		assertNull(IndexVersion.tokenOf("place_search", "place_search_2026072514"))
		// 다른 alias 이름은 섞이지 않는다
		assertNull(IndexVersion.tokenOf("place_vec", "place_search_20260725143022"))
	}

	@Test
	fun `나중 타임스탬프는 문자열 비교에서 항상 더 크다 (reconcile 불변식)`() {
		val older = "20260725090000"
		val newer = "20260725143022"
		val nextDay = "20260726000000"
		assertTrue(newer > older)
		assertTrue(nextDay > newer)
		// 최댓값 = 가장 최신 = 현재 alias 가 가리키는 것. 이 정렬이 reconcile 의 기준이다.
		assertEquals(nextDay, listOf(older, newer, nextDay).maxOrNull())
	}
}
