package dev.yubin.search.query

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.JsonpMapper
import dev.yubin.search.core.place.SearchDoc
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals

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
