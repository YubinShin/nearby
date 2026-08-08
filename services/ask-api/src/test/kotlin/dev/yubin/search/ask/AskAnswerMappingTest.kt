package dev.yubin.search.ask

import dev.yubin.search.ask.search.HsearchContract
import dev.yubin.search.ask.search.SearchPlatform
import dev.yubin.search.ask.search.SearchResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
@Import(AskAnswerMappingTest.HitsSearchPlatformConfig::class)
class AskAnswerMappingTest @Autowired constructor(
	private val ask: AskService,
	private val platform: HitsSearchPlatform,
) {
	@BeforeEach
	fun reset() {
		platform.hits = SASHIMI
	}

	@Test
	fun `the answer stage does not run unless the caller opts in`() = runTest {
		val response = ask.ask("회 먹을 데 있어?")

		assertNull(response.answer)
		assertEquals(0, response.answerTookMs)
		assertTrue(ANSWER !in response.degradedBy)
	}

	@Test
	fun `opting in grounds the answer in the records the search returned`() = runTest {
		val answer = assertNotNull(ask.ask("회 먹을 데 있어?", answer = true).answer)

		assertTrue(answer.found)
		assertTrue(answer.sentences.isNotEmpty())
		val cited = answer.sentences.flatMap { it.evidence }.toSet()
		assertTrue(cited.isNotEmpty())
		assertTrue(cited.all { it.startsWith("MA") })
	}

	@Test
	fun `the two llm stages fail independently`() = runTest {
		val response = ask.ask("회 먹을 데 있어?", answer = true)

		assertEquals(listOf(LLM), response.degradedBy)
		assertNotNull(response.answer)
	}

	@Test
	fun `an empty result set is answered as not found instead of invented`() = runTest {
		platform.hits = ""

		val answer = assertNotNull(ask.ask("회 먹을 데 있어?", answer = true).answer)

		assertFalse(answer.found)
		assertEquals(emptyList(), answer.sentences.single().evidence)
	}

	@Test
	fun `a record outside the query intent is left uncited`() = runTest {
		platform.hits = "$SASHIMI,$GARBAGE"

		val answer = assertNotNull(ask.ask("회 먹을 데 있어?", answer = true).answer)

		assertTrue(FAKE_ID !in answer.sentences.flatMap { it.evidence })
		assertEquals(emptyList(), answer.droppedEvidence)
	}

	@Test
	fun `a hit the contract cannot render is counted without disturbing the context`() = runTest {
		platform.hits = "$SASHIMI,$UNRENDERABLE"

		val answer = assertNotNull(ask.ask("회 먹을 데 있어?", answer = true).answer)

		assertEquals(1, answer.unrenderableRecords)
		assertTrue(answer.found)
	}

	@Test
	fun `a brand the model knows is answered from the records only`() = runTest {
		platform.hits = STARBUCKS

		val answer = assertNotNull(ask.ask("스타벅스 어때?", answer = true).answer)

		assertTrue(answer.found)
		assertEquals(emptyList(), answer.leakedTerms)
	}

	@Test
	fun `an unrecorded context degrades the answer without touching the search result`() = runTest {
		platform.hits = """{"placeId":"MA9","name":"녹화되지 않은 가게","label":"녹화되지 않은 가게","category":"카페","dong":"역삼동","address":"테헤란로 1"}"""

		val response = ask.ask("회 먹을 데 있어?", answer = true)

		assertNull(response.answer)
		assertTrue(ANSWER in response.degradedBy)
		assertNotNull(response.search)
	}

	@Test
	fun `a blank query never reaches the answer stage`() = runTest {
		val response = ask.ask("   ", answer = true)

		assertNull(response.answer)
		assertEquals(0, response.answerTookMs)
		assertEquals(emptyList(), response.degradedBy)
	}

	@TestConfiguration
	class HitsSearchPlatformConfig {
		@Bean
		@Primary
		fun hitsSearchPlatform(mapper: ObjectMapper) = HitsSearchPlatform(mapper)
	}

	private companion object {
		const val LLM = "llm"
		const val ANSWER = "answer"
		const val FAKE_ID = "FAKE0001"

		const val SASHIMI = """{"placeId":"MA010120220810147236","name":"먹어도","label":"먹어도","category":"횟집","dong":"삼성2동","address":"학동로56길 32"},
			{"placeId":"MA010120220813985043","name":"마시아","label":"마시아","category":"일식 회/초밥","dong":"삼성2동","address":"선릉로 514"},
			{"placeId":"MA010120220806498529","name":"어방참치","label":"어방참치","category":"일식 회/초밥","dong":"대치2동","address":"삼성로84길 32"},
			{"placeId":"MA0106202510A0703050","name":"카이","label":"카이","category":"일식 회/초밥","dong":"청담동","address":"학동로55길 12-11"},
			{"placeId":"MA010120220803440076","name":"네기","label":"네기","category":"일식 회/초밥","dong":"신사동","address":"도산대로15길 18"}"""

		const val STARBUCKS = """{"placeId":"MA0106202201A2363742","name":"서울세관사거리","brand":"스타벅스","label":"스타벅스 서울세관사거리","category":"카페","dong":"논현2동","address":"언주로 650"},
			{"placeId":"MA0106202201A2363717","name":"도산사거리","brand":"스타벅스","label":"스타벅스 도산사거리","category":"카페","dong":"논현2동","address":"언주로 727"},
			{"placeId":"MA0106202201A2363574","name":"청담사거리","brand":"스타벅스","label":"스타벅스 청담사거리","category":"카페","dong":"청담동","address":"도산대로 458"},
			{"placeId":"MA0106202201A2363846","name":"포이","brand":"스타벅스","label":"스타벅스 포이","category":"카페","dong":"개포4동","address":"논현로 88"},
			{"placeId":"MA0106202201A2363716","name":"압구정R","brand":"스타벅스","label":"스타벅스 압구정R","category":"카페","dong":"압구정동","address":"언주로 861"}"""

		const val GARBAGE = """{"placeId":"FAKE0001","name":"한길회계사무소","label":"한길회계사무소","category":"회계서비스","dong":"역삼동","address":"테헤란로 123"}"""

		const val UNRENDERABLE = """{"placeId":"MA9","category":"카페","dong":"역삼동","address":"테헤란로 1"}"""
	}
}

class HitsSearchPlatform(private val mapper: ObjectMapper) : SearchPlatform {
	var hits: String = ""

	override suspend fun hsearch(plan: SearchRequestPlan): SearchResult {
		val body: JsonNode = mapper.readTree("""{"total":0,"degraded":false,"channels":[],"hits":[$hits]}""")
		val decoded = HsearchContract.decode(body, mapper)
		return SearchResult(body, degraded = false, total = 0, records = decoded.records, unrenderable = decoded.unrenderable)
	}
}
