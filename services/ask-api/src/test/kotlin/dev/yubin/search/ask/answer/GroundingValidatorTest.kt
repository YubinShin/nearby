package dev.yubin.search.ask.answer

import dev.yubin.search.ask.Answer
import dev.yubin.search.ask.AnswerSentence
import dev.yubin.search.ask.search.PlaceRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
class GroundingValidatorTest @Autowired constructor(private val validator: GroundingValidator) {
	@Test
	fun `a citation the search never returned is dropped and named`() {
		val validated = validator.validate(
			answer("삼성2동에 먹어도가 있습니다.", "MA1", "FAKE0001"),
			RECORDS,
		)

		assertEquals(listOf("MA1"), validated.sentences.single().evidence)
		assertEquals(listOf("FAKE0001"), validated.droppedEvidence)
	}

	@Test
	fun `the sentence survives the drop so a groundless claim stays visible`() {
		val validated = validator.validate(answer("근거 없는 문장입니다.", "FAKE0001"), RECORDS)

		assertEquals("근거 없는 문장입니다.", validated.sentences.single().text)
		assertEquals(emptyList(), validated.sentences.single().evidence)
	}

	@Test
	fun `a citation whose name is absent from the sentence is a warning, not a drop`() {
		val validated = validator.validate(answer("삼성2동에 두 곳이 있습니다.", "MA1", "MA2"), RECORDS)

		assertEquals(listOf("MA1", "MA2"), validated.sentences.single().evidence)
		assertEquals(listOf("MA1", "MA2"), validated.driftingEvidence)
	}

	@Test
	fun `a citation whose name appears in the sentence does not drift`() {
		val validated = validator.validate(answer("먹어도가 있습니다.", "MA1"), RECORDS)

		assertEquals(emptyList(), validated.driftingEvidence)
	}

	@Test
	fun `an attribute the corpus cannot verify is reported as a leak`() {
		val validated = validator.validate(answer("먹어도는 맛있고 인기가 많습니다.", "MA1"), RECORDS)

		assertTrue("맛" in validated.leakedTerms)
		assertTrue("인기도" in validated.leakedTerms)
	}

	@Test
	fun `a business name carrying a forbidden term is not read as a leak`() {
		val validated = validator.validate(
			answer("맛있는집이 있습니다.", "MA3"),
			RECORDS + PlaceRecord("MA3", "맛있는집", "맛있는집", "한식", "역삼동", "테헤란로 1"),
		)

		assertEquals(emptyList(), validated.leakedTerms)
	}

	@Test
	fun `a grounded answer reports nothing`() {
		val validated = validator.validate(answer("먹어도가 있습니다.", "MA1"), RECORDS)

		assertEquals(emptyList(), validated.droppedEvidence)
		assertEquals(emptyList(), validated.driftingEvidence)
		assertEquals(emptyList(), validated.leakedTerms)
	}

	@Test
	fun `the conditions the model could not verify are carried through untouched`() {
		val validated = validator.validate(
			Answer(found = true, unverifiableConditions = listOf("맛있고", "가까운"), sentences = listOf(AnswerSentence("먹어도가 있습니다.", listOf("MA1")))),
			RECORDS,
		)

		assertEquals(listOf("맛있고", "가까운"), validated.unverifiableConditions)
	}

	private fun answer(text: String, vararg evidence: String) =
		Answer(found = true, sentences = listOf(AnswerSentence(text, evidence.toList())))

	private companion object {
		val RECORDS = listOf(
			PlaceRecord("MA1", "먹어도", "먹어도", "횟집", "삼성2동", "학동로56길 32"),
			PlaceRecord("MA2", "마시아", "마시아", "일식 회/초밥", "삼성2동", "선릉로 514"),
		)
	}
}
