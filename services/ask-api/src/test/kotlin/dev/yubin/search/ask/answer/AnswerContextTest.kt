package dev.yubin.search.ask.answer

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
class AnswerContextTest @Autowired constructor(
	private val context: AnswerContext,
	private val mapper: ObjectMapper,
) {
	@Test
	fun `hits become records the validator can match by place id`() {
		val records = context.records(body(SASHIMI))

		assertEquals(5, records.size)
		assertEquals("MA010120220810147236", records.first().placeId)
		assertEquals("먹어도", records.first().name)
		assertEquals("횟집", records.first().category)
	}

	@Test
	fun `a hit without a place id or a name is not rendered as evidence`() {
		val records = context.records(
			body("""{"placeId":"","name":"이름만"},{"placeId":"MA1","name":""},{"placeId":"MA2","name":"정상"}"""),
		)

		assertEquals(listOf("MA2"), records.map { it.placeId })
	}

	@Test
	fun `an empty result renders as zero hits instead of an empty list`() {
		assertEquals("검색결과: (0건)", context.render(emptyList()))
	}

	@Test
	fun `a missing field is dropped from the line instead of printing null`() {
		val rendered = context.render(listOf(AnswerRecord("MA1", "이름", category = null, dong = "역삼동", address = null)))

		assertEquals("검색결과 (거리 정보 없음):\n- [MA1] 이름 | 역삼동", rendered)
	}

	@Test
	fun `the header states that distance is absent so the model cannot report it`() {
		assertEquals("검색결과 (거리 정보 없음):", context.render(context.records(body(SASHIMI))).lineSequence().first())
	}

	private fun body(hits: String) = mapper.readTree("""{"total":5,"degraded":false,"hits":[$hits]}""")

	private companion object {
		const val SASHIMI = """
			{"placeId":"MA010120220810147236","name":"먹어도","category":"횟집","dong":"삼성2동","address":"학동로56길 32"},
			{"placeId":"MA010120220813985043","name":"마시아","category":"일식 회/초밥","dong":"삼성2동","address":"선릉로 514"},
			{"placeId":"MA010120220806498529","name":"어방참치","category":"일식 회/초밥","dong":"대치2동","address":"삼성로84길 32"},
			{"placeId":"MA0106202510A0703050","name":"카이","category":"일식 회/초밥","dong":"청담동","address":"학동로55길 12-11"},
			{"placeId":"MA010120220803440076","name":"네기","category":"일식 회/초밥","dong":"신사동","address":"도산대로15길 18"}
		"""
	}
}
