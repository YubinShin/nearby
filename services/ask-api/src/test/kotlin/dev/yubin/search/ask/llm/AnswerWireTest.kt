package dev.yubin.search.ask.llm

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
class AnswerWireTest @Autowired constructor(
	private val prompt: AnswerPromptSpec,
	private val mapper: ObjectMapper,
) {
	@Test
	fun `the response schema pins the three fields the validator reads`() {
		val properties = prompt.responseSchema["properties"] as Map<*, *>
		assertEquals(setOf("found", "unverifiable_conditions", "sentences"), properties.keys)
		assertEquals(listOf("found", "unverifiable_conditions", "sentences"), prompt.responseSchema["required"])
	}

	@Test
	fun `every sentence is required to carry an evidence array`() {
		val sentences = (prompt.responseSchema["properties"] as Map<*, *>)["sentences"] as Map<*, *>
		val item = sentences["items"] as Map<*, *>
		assertEquals(listOf("text", "evidence"), item["required"])
	}

	@Test
	fun `the request replays the thinking level the experiment measured`() {
		val config = prompt.request("회 먹을 데 있어?", "검색결과: (0건)")["generationConfig"] as Map<*, *>
		assertEquals(mapOf("thinkingLevel" to "minimal"), config["thinkingConfig"])
		assertEquals("application/json", config["responseMimeType"])
		assertEquals(prompt.responseSchema, config["responseSchema"])
	}

	@Test
	fun `the prompt carries the question, the records and the grounding instruction in that order`() {
		val text = prompt.text("회 먹을 데 있어?", "검색결과: (0건)")

		assertTrue(text.startsWith("질문: 회 먹을 데 있어?"))
		assertTrue("검색결과: (0건)" in text)
		assertTrue(text.endsWith(prompt.system))
	}

	@Test
	fun `a schema-shaped payload decodes into the answer`() {
		val answer = AnswerWire.decode(
			envelope(
				"""{"found":true,"unverifiable_conditions":["맛있고"],
				   "sentences":[{"text":"삼성2동에 먹어도가 있습니다.","evidence":["MA1"]}]}""",
			),
			mapper,
		)

		assertEquals(true, answer.found)
		assertEquals(listOf("맛있고"), answer.unverifiableConditions)
		assertEquals("삼성2동에 먹어도가 있습니다.", answer.sentences.single().text)
		assertEquals(listOf("MA1"), answer.sentences.single().evidence)
	}

	@Test
	fun `a not-found answer decodes without sentences`() {
		val answer = AnswerWire.decode(
			envelope("""{"found":false,"unverifiable_conditions":["어방참치"],"sentences":[]}"""),
			mapper,
		)

		assertEquals(false, answer.found)
		assertEquals(emptyList(), answer.sentences)
	}

	@Test
	fun `a blank sentence is dropped instead of reaching the validator`() {
		val answer = AnswerWire.decode(
			envelope(
				"""{"found":true,"unverifiable_conditions":[],
				   "sentences":[{"text":"  ","evidence":["MA1"]},{"text":"먹어도가 있습니다.","evidence":["MA1"]}]}""",
			),
			mapper,
		)

		assertEquals(1, answer.sentences.size)
		assertEquals("먹어도가 있습니다.", answer.sentences.single().text)
	}

	@Test
	fun `found with no sentence at all is an llm failure`() {
		val failure = assertFailsWith<LlmException> {
			AnswerWire.decode(envelope("""{"found":true,"unverifiable_conditions":[],"sentences":[]}"""), mapper)
		}
		assertTrue(failure.message!!.contains("found=true"))
	}

	@Test
	fun `a payload that is not json is an llm failure`() {
		assertFailsWith<LlmException> { AnswerWire.decode(envelope("죄송하지만 도와드릴 수 없습니다"), mapper) }
	}

	@Test
	fun `a blocked prompt with no candidate is an llm failure`() {
		assertFailsWith<LlmException> { AnswerWire.decode(GeminiResponse(), mapper) }
	}

	private fun envelope(text: String) =
		GeminiResponse(candidates = listOf(GeminiCandidate(content = GeminiContent(parts = listOf(GeminiPart(text))))))
}
