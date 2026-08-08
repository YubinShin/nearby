package dev.yubin.search.ask.answer

import dev.yubin.search.ask.search.PlaceRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
class AnswerContextTest @Autowired constructor(private val context: AnswerContext) {
	@Test
	fun `a record renders as one citable line`() {
		val rendered = context.render(listOf(SASHIMI))

		assertEquals(
			"검색결과 (거리 정보 없음):\n- [MA010120220810147236] 먹어도 | 횟집 | 삼성2동 | 학동로56길 32",
			rendered,
		)
	}

	@Test
	fun `an empty result renders as zero hits instead of an empty list`() {
		assertEquals("검색결과: (0건)", context.render(emptyList()))
	}

	@Test
	fun `a missing field is dropped from the line instead of printing null`() {
		val rendered = context.render(listOf(PlaceRecord("MA1", "이름", "이름", category = null, dong = "역삼동", address = null)))

		assertEquals("검색결과 (거리 정보 없음):\n- [MA1] 이름 | 역삼동", rendered)
	}

	@Test
	fun `the header states that distance is absent so the model cannot report it`() {
		assertEquals("검색결과 (거리 정보 없음):", context.render(listOf(SASHIMI)).lineSequence().first())
	}

	private companion object {
		val SASHIMI = PlaceRecord("MA010120220810147236", "먹어도", "먹어도", "횟집", "삼성2동", "학동로56길 32")
	}
}
