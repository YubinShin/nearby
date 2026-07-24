package dev.yubin.search.query

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.JsonpMapper
import dev.yubin.search.core.place.SearchDoc
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals

/**
 * ES `_source` 가 [SearchDoc] 으로 **제대로 채워지는지** 잰다.
 *
 * 이게 깨지면 증상이 고약하다: 질의는 성공하고 total 도 맞고 하이라이트도 나오는데
 * (그건 ES 메타데이터라서) `_source` 에서 오는 이름·주소만 빈 문자열이 된다. 200 OK 에
 * 에러 로그도 없다. 실측에서 `/v1/search?q=CU` 가 187건을 돌려주면서 이름이 전부 `""` 인
 * 걸 보고 만든 테스트다.
 *
 * 앱이 실제로 쓰는 [JsonpMapper] 를 그대로 쓴다 — 별도 ObjectMapper 로 재면 "테스트는
 * 통과하는데 앱은 비어 있는" 상황을 못 잡는다. 이 클래스가 Kotlin data class(`val` 뿐,
 * 세터 없음)라 Jackson 의 Kotlin 모듈이 빠지면 조용히 기본값으로 채워진다.
 */
@SpringBootTest
class SearchDocDeserializationTest {

	@Autowired
	private lateinit var es: ElasticsearchClient

	@Test
	fun `_source 가 SearchDoc 으로 채워진다`() {
		val source = """
			{
			  "place_id": "MA010120220800117084",
			  "name": "CU",
			  "branch": "선릉역점",
			  "brand": "CU",
			  "brand_text": "CU 씨유",
			  "category_large": "소매",
			  "category_mid": "종합 소매",
			  "category_small": "편의점",
			  "sido": "서울특별시",
			  "sigungu": "강남구",
			  "dong": "삼성2동",
			  "jibun_address": "서울특별시 강남구 삼성동 141-33",
			  "road_address": "서울특별시 강남구 테헤란로 403",
			  "location": { "lat": 37.505079192706, "lon": 127.04951398867 },
			  "updated_at": "2026-07-22T09:33:08.618994Z"
			}
		""".trimIndent()

		val mapper: JsonpMapper = es._jsonpMapper()
		val parser = mapper.jsonProvider().createParser(ByteArrayInputStream(source.toByteArray()))
		val doc = mapper.deserialize(parser, SearchDoc::class.java)

		assertEquals("MA010120220800117084", doc.place_id)
		assertEquals("CU", doc.name)
		assertEquals("선릉역점", doc.branch)
		assertEquals("강남구", doc.sigungu)
		assertEquals(37.505079192706, doc.location?.lat)
	}
}
