package dev.yubin.search.ask

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
@Import(AskMappingTest.StubSearchPlatformConfig::class)
class AskMappingTest @Autowired constructor(
	private val ask: AskService,
	private val platform: RecordingSearchPlatform,
) {
	@BeforeEach
	fun reset() {
		platform.degraded = false
		platform.lastPlan = null
	}

	@Test
	fun `a meaning-only query becomes a keyword plus category hint search`() = runTest {
		val response = ask.ask("회 먹을 데")

		assertEquals("회 횟집", platform.lastPlan?.q)
		assertEquals("회", response.parsed?.keyword)
		assertEquals("횟집", response.parsed?.categoryHint)
		assertFalse(response.degraded)
	}

	@Test
	fun `a plain category query is passed through without extra tokens`() = runTest {
		val response = ask.ask("카페")

		assertEquals("카페", assertNotNull(platform.lastPlan).q)
		assertNull(assertNotNull(response.parsed).categoryHint)
		assertEquals(emptyList(), response.applied.unmapped)
	}

	@Test
	fun `a brand query keeps the brand as the keyword and adds its category`() = runTest {
		val response = ask.ask("스타벅스")

		assertEquals("스타벅스 카페", platform.lastPlan?.q)
		assertEquals("카페", response.parsed?.categoryHint)
	}

	@Test
	fun `a geo anchor rides in the query text and is reported as unmapped`() = runTest {
		val response = ask.ask("역삼동 조용히 공부할 곳")

		assertEquals("역삼동 공부할 곳 스터디카페", platform.lastPlan?.q)
		assertEquals("역삼동", response.parsed?.geoAnchor)
		assertTrue("geo_anchor" in response.applied.unmapped)
	}

	@Test
	fun `a parsed radius reaches hsearch only when the caller sent coordinates`() = runTest {
		val without = ask.ask("강남역 500m 안에 편의점")
		assertNull(assertNotNull(platform.lastPlan).radius)
		assertTrue("radius_m" in without.applied.unmapped)

		val with = ask.ask("강남역 500m 안에 편의점", lat = 37.4979, lon = 127.0276)
		assertEquals(500, assertNotNull(platform.lastPlan).radius)
		assertTrue("radius_m" !in with.applied.unmapped)
	}

	@Test
	fun `a trap query is flagged but still searched`() = runTest {
		val response = ask.ask("평점 4.5 이상 카페")

		assertEquals(true, response.parsed?.expectsEmpty)
		assertEquals("카페", platform.lastPlan?.q)
	}

	@Test
	fun `the dropped filter is named in the response instead of vanishing`() = runTest {
		val response = ask.ask("평점 4.5 이상 카페")

		assertEquals(listOf("평점"), response.applied.unsupported)
		assertEquals("카페", platform.lastPlan?.q)
	}

	@Test
	fun `naming the dropped filter does not narrow the search`() = runTest {
		val response = ask.ask("배달 되는 치킨집")

		assertEquals(listOf("배달"), response.applied.unsupported)
		assertEquals("치킨집", platform.lastPlan?.q)
		assertFalse(response.degraded)
	}

	@Test
	fun `an ordinary query reports nothing unsupported`() = runTest {
		val response = ask.ask("카페")

		assertEquals(emptyList(), response.applied.unsupported)
	}

	@Test
	fun `the dropped filter is reported even when the llm is down`() = runTest {
		val response = ask.ask("평점 높은 녹화되지 않은 질의")

		assertNull(response.parsed)
		assertEquals(listOf("llm"), response.degradedBy)
		assertEquals(listOf("평점"), response.applied.unsupported)
		assertEquals("평점 높은 녹화되지 않은 질의", assertNotNull(platform.lastPlan).q)
	}

	@Test
	fun `downstream degraded is propagated`() = runTest {
		platform.degraded = true

		val response = ask.ask("카페")

		assertTrue(response.degraded)
		assertEquals(listOf("search"), response.degradedBy)
	}

	@Test
	fun `a parse failure degrades to the raw query instead of failing the request`() = runTest {
		val response = ask.ask("녹화되지 않은 질의")

		assertNull(response.parsed)
		assertEquals(listOf("llm"), response.degradedBy)
		assertEquals("녹화되지 않은 질의", platform.lastPlan?.q)
		assertNotNull(response.search)
	}

	@Test
	fun `a blank query never calls the llm and is not degraded`() = runTest {
		val response = ask.ask("   ")

		assertNull(response.parsed)
		assertEquals(emptyList(), response.degradedBy)
		assertEquals(0, response.llmTookMs)
		assertEquals("", platform.lastPlan?.q)
	}

	@TestConfiguration
	class StubSearchPlatformConfig {
		@Bean
		@Primary
		fun recordingSearchPlatform(mapper: ObjectMapper) = RecordingSearchPlatform(mapper)
	}
}

class RecordingSearchPlatform(private val mapper: ObjectMapper) : SearchPlatform {
	var lastPlan: SearchRequestPlan? = null
	var degraded = false

	override suspend fun hsearch(plan: SearchRequestPlan): SearchResult {
		lastPlan = plan
		val body: JsonNode = mapper.readTree("""{"total":3,"degraded":$degraded,"channels":[],"hits":[]}""")
		return SearchResult(body, degraded, 3)
	}
}
