package dev.yubin.search.ask

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(properties = ["psp.ask.llm=fixture", "psp.ask.budget-ms=0"])
@Import(AskBudgetTest.StubSearchPlatformConfig::class)
class AskBudgetTest @Autowired constructor(private val ask: AskService) {
	@Test
	fun `an exhausted budget degrades the understanding stage instead of running it`() = runTest {
		val response = ask.ask("회 먹을 데")

		assertNull(response.parsed)
		assertTrue(AskBudgetTest.LLM in response.degradedBy)
	}

	@Test
	fun `an exhausted budget still returns the search result`() = runTest {
		val response = ask.ask("회 먹을 데")

		assertEquals("회 먹을 데", response.applied.q)
		assertTrue(response.degraded)
	}

	@Test
	fun `an exhausted budget degrades the answer stage too`() = runTest {
		val response = ask.ask("회 먹을 데", answer = true)

		assertNull(response.answer)
		assertTrue(AskBudgetTest.ANSWER in response.degradedBy)
	}

	@TestConfiguration
	class StubSearchPlatformConfig {
		@Bean
		@Primary
		fun budgetSearchPlatform(mapper: ObjectMapper) = RecordingSearchPlatform(mapper)
	}

	private companion object {
		const val LLM = "llm"
		const val ANSWER = "answer"
	}
}
