package dev.yubin.search.ask.llm

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
class GeminiWireTest @Autowired constructor(
	private val prompt: AskPromptSpec,
	private val mapper: ObjectMapper,
) {
	@Test
	fun `the response schema pins the five fields the planner reads`() {
		val properties = prompt.responseSchema["properties"] as Map<*, *>
		assertEquals(
			setOf("keyword", "category_hint", "geo_anchor", "radius_m", "expects_empty"),
			properties.keys,
		)
		assertEquals(listOf("keyword", "expects_empty"), prompt.responseSchema["required"])
	}

	@Test
	fun `the request asks for json at temperature zero`() {
		val config = prompt.request("카페")["generationConfig"] as Map<*, *>
		assertEquals(0, config["temperature"])
		assertEquals("application/json", config["responseMimeType"])
		assertEquals(prompt.responseSchema, config["responseSchema"])
	}

	@Test
	fun `a schema-shaped payload decodes into the parsed query`() {
		val parsed = GeminiWire.decode(
			envelope("""{"keyword":"회","category_hint":"횟집","geo_anchor":null,"radius_m":null,"expects_empty":false}"""),
			mapper,
		)
		assertEquals("회", parsed.keyword)
		assertEquals("횟집", parsed.categoryHint)
		assertNull(parsed.geoAnchor)
		assertEquals(false, parsed.expectsEmpty)
	}

	@Test
	fun `blank strings are normalised to null`() {
		val parsed = GeminiWire.decode(
			envelope("""{"keyword":" 카페 ","category_hint":"  ","geo_anchor":"","expects_empty":false}"""),
			mapper,
		)
		assertEquals("카페", parsed.keyword)
		assertNull(parsed.categoryHint)
		assertNull(parsed.geoAnchor)
	}

	@Test
	fun `a blocked prompt with no candidate is an llm failure`() {
		val failure = assertFailsWith<LlmException> { GeminiWire.decode(GeminiResponse(), mapper) }
		assertTrue(failure.message!!.contains("no candidate"))
	}

	@Test
	fun `a payload that is not json is an llm failure`() {
		assertFailsWith<LlmException> { GeminiWire.decode(envelope("죄송하지만 도와드릴 수 없습니다"), mapper) }
	}

	@Test
	fun `an empty keyword is an llm failure`() {
		assertFailsWith<LlmException> { GeminiWire.decode(envelope("""{"keyword":"","expects_empty":false}"""), mapper) }
	}

	private fun envelope(text: String) =
		GeminiResponse(candidates = listOf(GeminiCandidate(content = GeminiContent(parts = listOf(GeminiPart(text))))))
}
