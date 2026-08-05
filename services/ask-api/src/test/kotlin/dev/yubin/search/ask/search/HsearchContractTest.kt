package dev.yubin.search.ask.search

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
class HsearchContractTest @Autowired constructor(private val mapper: ObjectMapper) {
	@Test
	fun `the contract names the fields the answer stage renders`() {
		assertEquals(listOf("placeId", "name"), HsearchContract.REQUIRED)
		assertEquals(listOf("category", "dong", "address"), HsearchContract.OPTIONAL)
	}

	@Test
	fun `a documented hsearch hit decodes into a record`() {
		val decoded = HsearchContract.decode(body(DOCUMENTED), mapper)

		assertEquals(0, decoded.unrenderable)
		assertEquals(
			PlaceRecord("MA010120220810147236", "먹어도", "횟집", "삼성2동", "서울특별시 강남구 학동로56길 32"),
			decoded.records.single(),
		)
	}

	@Test
	fun `ranking fields the answer never renders are ignored`() {
		val decoded = HsearchContract.decode(body(DOCUMENTED), mapper)

		assertEquals(1, decoded.records.size)
		assertEquals(0, decoded.unrenderable)
	}

	@Test
	fun `a hit that lost a required field is counted instead of silently dropped`() {
		val decoded = HsearchContract.decode(
			body("""{"placeId":"MA1","name":"정상","category":"카페"},{"placeId":"MA2","category":"카페"}"""),
			mapper,
		)

		assertEquals(listOf("MA1"), decoded.records.map { it.placeId })
		assertEquals(1, decoded.unrenderable)
	}

	@Test
	fun `a renamed optional field leaves the record renderable with a hole`() {
		val decoded = HsearchContract.decode(
			body("""{"placeId":"MA1","name":"정상","category":"카페","roadAddress":"테헤란로 1"}"""),
			mapper,
		)

		assertEquals(0, decoded.unrenderable)
		assertEquals(null, decoded.records.single().address)
	}

	@Test
	fun `an empty result set is neither records nor a contract break`() {
		val decoded = HsearchContract.decode(body(""), mapper)

		assertEquals(emptyList(), decoded.records)
		assertEquals(0, decoded.unrenderable)
	}

	private fun body(hits: String) = mapper.readTree("""{"total":1,"degraded":false,"channels":[],"hits":[$hits]}""")

	private companion object {
		const val DOCUMENTED = """{"placeId":"MA010120220810147236","name":"먹어도","branch":null,"brand":null,
			"category":"횟집","address":"서울특별시 강남구 학동로56길 32","sigungu":"강남구","dong":"삼성2동",
			"lat":37.51518,"lon":127.04282,"score":0.01639,"distanceM":null,"highlight":[],"label":"먹어도",
			"ranks":{"vector":1},"scores":{"vector":0.872}}"""
	}
}
