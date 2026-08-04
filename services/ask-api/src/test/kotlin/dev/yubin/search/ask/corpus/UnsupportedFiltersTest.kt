package dev.yubin.search.ask.corpus

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
class UnsupportedFiltersTest @Autowired constructor(private val filters: UnsupportedFilters) {
	@Test
	fun `the lexicon covers every attribute the prompt calls missing`() {
		assertEquals(
			setOf("평점", "리뷰", "인기도", "영업시간", "가격", "배달", "메뉴", "사진"),
			filters.names.toSet(),
		)
	}

	@Test
	fun `the four attribute traps in the golden set are detected`() {
		assertEquals(listOf("평점"), filters.detect("평점 4.5 이상 카페"))
		assertEquals(listOf("영업시간"), filters.detect("지금 문 연 약국"))
		assertEquals(listOf("배달"), filters.detect("배달 되는 치킨집"))
		assertEquals(listOf("가격"), filters.detect("1만원 이하 파스타"))
	}

	@Test
	fun `the out-of-area trap is not an attribute problem`() {
		assertEquals(emptyList(), filters.detect("제주도 흑돼지 맛집"))
	}

	@Test
	fun `the regression queries stay clean`() {
		listOf(
			"회 먹을 데", "브런치 먹을 곳", "머리 자르는 곳", "혼밥하기 좋은 집", "매운 거 먹고 싶다",
			"차 고치는 곳", "조용히 공부할 곳", "고기 구워 먹는 데", "카페", "편의점", "세탁소",
			"약국", "미용실", "치킨", "삼겹살", "노래방", "스타벅스", "CU", "파리바게뜨", "이디야",
		).forEach { assertEquals(emptyList(), filters.detect(it), it) }
	}

	@Test
	fun `whitespace between the words does not hide a match`() {
		assertEquals(listOf("영업시간"), filters.detect("지금  문   연 약국"))
		assertEquals(listOf("영업시간"), filters.detect("지금문연약국"))
	}

	@Test
	fun `a query can trip more than one attribute`() {
		assertEquals(listOf("평점", "가격"), filters.detect("평점 높고 가성비 좋은 카페"))
	}

	@Test
	fun `a blank query detects nothing`() {
		assertEquals(emptyList(), filters.detect(""))
		assertEquals(emptyList(), filters.detect("   "))
	}

	@Test
	fun `place names that merely contain a term are mostly left alone`() {
		listOf("포장마차", "사진관", "새벽집", "24시해장국", "착한가격헤어", "냉면&덮밥도배달돼지")
			.forEach { assertEquals(emptyList(), filters.detect(it), it) }
	}

	@Test
	fun `the known collision is a branch name ending in 점`() {
		assertTrue("평점" in filters.detect("씨유강남거평점"))
	}
}
