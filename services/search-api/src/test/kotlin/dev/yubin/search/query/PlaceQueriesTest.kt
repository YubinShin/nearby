package dev.yubin.search.query

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.json.JsonpUtils
import co.elastic.clients.json.SimpleJsonpMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceQueriesTest {
	private fun json(q: Query): String = JsonpUtils.toJsonString(q, SimpleJsonpMapper.INSTANCE)

	@Test
	fun `본문 검색은 모든 단어를 요구하는 cross_fields 다`() {
		val q = json(PlaceQueries.search(SearchRequest.of("역삼 커피")))

		assertTrue("cross_fields" in q, "필드에 흩어진 단어를 묶어 보려면 cross_fields 여야 한다: $q")
		assertTrue("\"operator\":\"and\"" in q, "엄격 질의는 모든 단어를 요구한다: $q")
		assertTrue("역삼 커피" in q)
	}

	@Test
	fun `상호명 가중치가 가장 높고 주소는 보조다`() {
		assertEquals("name^5", PlaceQueries.SEARCH_FIELDS.first())
		assertTrue("road_address" in PlaceQueries.SEARCH_FIELDS)

		assertTrue(PlaceQueries.SEARCH_FIELDS.any { it.startsWith("category_small.txt") })
		assertTrue(PlaceQueries.SEARCH_FIELDS.any { it.startsWith("dong.txt") })
	}

	@Test
	fun `상호명 구절 일치에 가산점을 준다`() {
		val q = json(PlaceQueries.search(SearchRequest.of("스타벅스")))
		assertTrue("match_phrase" in q, "이름이 통째로 맞는 곳을 위로 올려야 한다: $q")
	}

	@Test
	fun `폴백 질의는 모든 단어 요구를 푼다`() {
		val strict = json(PlaceQueries.search(SearchRequest.of("역삼 커피 브런치"), relaxed = false))
		val relaxed = json(PlaceQueries.search(SearchRequest.of("역삼 커피 브런치"), relaxed = true))

		assertTrue("\"operator\":\"and\"" in strict)
		assertFalse("\"operator\":\"and\"" in relaxed)

		assertTrue("\"minimum_should_match\":\"70%\"" in relaxed, "폴백 임계값이 바뀌었다: $relaxed")
	}

	@Test
	fun `필터가 없으면 필터 절도 없다`() {
		assertTrue(PlaceQueries.filters(SearchRequest.of("커피")).isEmpty())
	}

	@Test
	fun `행정구역 필터는 분석되지 않은 keyword 원본을 쓴다`() {
		val filters = PlaceQueries.filters(SearchRequest.of("커피", sigungu = "강남구", dong = "역삼동"))
		val q = filters.joinToString { json(it) }

		assertEquals(2, filters.size)
		assertTrue("\"sigungu\"" in q && "dong.txt" !in q, "정확 일치 필터는 .txt 가 아니라 keyword 원본: $q")
		assertTrue("강남구" in q && "역삼동" in q)
	}

	@Test
	fun `좌표가 있으면 반경 필터가 미터 단위로 붙는다`() {
		val filters = PlaceQueries.filters(
			SearchRequest.of("커피", lat = 37.5006, lon = 127.0366, radiusM = 800),
		)
		val q = json(filters.single())

		assertTrue("geo_distance" in q, q)
		assertTrue("800m" in q, q)
		assertTrue("37.5006" in q && "127.0366" in q, q)
	}

	@Test
	fun `자동완성은 접두 일치와 이름 길이로 순서를 잡는다`() {
		val q = json(PlaceQueries.suggest(SuggestRequest.of("스타")))

		assertTrue("function_score" in q, q)

		assertTrue("\"label\"" in q, "자동완성은 label 로 매칭해야 한다: $q")
		assertTrue("\"name\"" !in q, "name 으로 되돌아가면 브랜드 복원이 무의미해진다: $q")
		assertTrue("prefix" in q, "이름이 그 글자로 시작하면 올려야 한다: $q")
		assertTrue("name_length" in q && "reciprocal" in q, "짧은 이름 우선(길이 역수): $q")
		assertTrue("multiply" in q, "BM25 점수에 곱해야 원래 관련도가 유지된다: $q")
	}

	@Test
	fun `자동완성 접두 매칭은 대소문자를 가리지 않는다`() {
		assertTrue("starbucks" in json(PlaceQueries.suggest(SuggestRequest.of("StarBucks"))))
	}
}
