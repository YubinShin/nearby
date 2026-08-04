package dev.yubin.search.ask.llm

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.DefaultResourceLoader
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
class FixtureLlmClientTest @Autowired constructor(
	private val prompt: AskPromptSpec,
	private val mapper: ObjectMapper,
) {
	private val resources = DefaultResourceLoader()

	@Test
	fun `a location without an index fails at startup instead of degrading every request`() {
		val failure = assertFailsWith<IllegalStateException> { client("classpath:fixtures-that-are-not-shipped/") }

		assertTrue(failure.message!!.contains("psp.ask.fixtures.location"), failure.message)
	}

	@Test
	fun `an entry recorded with an older prompt version is named in the startup warning`(@TempDir dir: Path) {
		dir.resolve("index.json").writeText(
			"""
			{"promptVersion":"${prompt.version}","model":"m","entries":{
			  "카페":{"file":"a.json","source":"recorded","model":"m","promptVersion":"0"}}}
			""".trimIndent(),
		)

		val warnings = warningsWhile { client("file:${dir.toAbsolutePath()}/") }

		assertEquals(1, warnings.size, warnings.toString())
		assertTrue(warnings.single().contains("카페"), warnings.toString())
	}

	@Test
	fun `the shipped fixtures are all recorded with the prompt version this build carries`() {
		val warnings = warningsWhile { client("classpath:fixtures/") }

		assertEquals(emptyList(), warnings)
	}

	private fun client(location: String) = FixtureLlmClient(location, prompt, mapper, resources)

	private fun warningsWhile(block: () -> Unit): List<String> {
		val logger = LoggerFactory.getLogger(FixtureLlmClient::class.java) as Logger
		val appender = ListAppender<ILoggingEvent>()
		appender.start()
		logger.addAppender(appender)
		try {
			block()
		} finally {
			logger.detachAppender(appender)
		}
		return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
	}
}
