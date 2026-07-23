package dev.yubin.search.query

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.json.JsonpUtils
import co.elastic.clients.json.SimpleJsonpMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 랭킹 규칙을 **JSON 으로 고정**한다. ES 없이 도는 순수 단위 테스트다.
 *
 * "이렇게 짰다"가 아니라 "이 질의가 나가야 한다"를 못 박는 게 목적 —
 * 나중에 필드 가중치를 손댔을 때 의도치 않은 변화가 여기서 걸린다.
 */
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
		// 카테고리·행정동은 keyword 원본이 아니라 형태소 분석된 .txt 로 매칭해야 부분 단어가 걸린다.
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
		// 3단어면 2단어는 맞아야 한다 — 완전히 풀어버리면 폴백 결과가 소음이 된다.
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
		assertTrue("prefix" in q, "이름이 그 글자로 시작하면 올려야 한다: $q")
		assertTrue("name_length" in q && "reciprocal" in q, "짧은 이름 우선(길이 역수): $q")
		assertTrue("multiply" in q, "BM25 점수에 곱해야 원래 관련도가 유지된다: $q")
	}

	@Test
	fun `자동완성 접두 매칭은 대소문자를 가리지 않는다`() {
		// name_raw 에 lowercase normalizer 를 걸었으므로 질의도 소문자로 맞춰야 걸린다.
		assertTrue("starbucks" in json(PlaceQueries.suggest(SuggestRequest.of("StarBucks"))))
	}
}
