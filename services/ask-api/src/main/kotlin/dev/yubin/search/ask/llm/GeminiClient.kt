package dev.yubin.search.ask.llm

import dev.yubin.search.ask.Answer
import dev.yubin.search.ask.ParsedQuery
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import reactor.util.retry.Retry
import tools.jackson.databind.ObjectMapper
import java.time.Duration

@Component
@ConditionalOnProperty(name = ["psp.ask.llm"], havingValue = "gemini")
class GeminiClient(
	@Value("\${psp.ask.gemini.base-url}") baseUrl: String,
	@Value("\${psp.ask.gemini.model}") private val model: String,
	@Value("\${psp.ask.gemini.api-key}") apiKey: String,
	@Value("\${psp.ask.gemini.timeout-ms}") timeoutMs: Long,
	private val prompt: AskPromptSpec,
	private val answerPrompt: AnswerPromptSpec,
	private val mapper: ObjectMapper,
) : LlmClient {
	init {
		require(apiKey.isNotBlank()) {
			"GEMINI_API_KEY is not set. export it, or replay recorded responses with " +
				"--psp.ask.llm=fixture --psp.ask.fixtures.location=file:src/test/resources/fixtures/"
		}
	}

	private val connections = ConnectionProvider.builder("gemini")
		.maxConnections(MAX_CONNECTIONS)
		.pendingAcquireTimeout(ACQUIRE_TIMEOUT)
		.build()

	private val http = WebClient.builder()
		.baseUrl(baseUrl)
		.defaultHeader(API_KEY_HEADER, apiKey)
		.clientConnector(
			ReactorClientHttpConnector(
				HttpClient.create(connections).responseTimeout(Duration.ofMillis(timeoutMs)),
			),
		)
		.build()

	override val vendor = "gemini"

	@PreDestroy
	fun close() {
		connections.disposeLater().block(DISPOSE_TIMEOUT)
	}

	override suspend fun parse(query: String): ParsedQuery =
		GeminiWire.decode(generate(prompt.request(query)), mapper)

	override suspend fun answer(question: String, context: String): Answer =
		AnswerWire.decode(generate(answerPrompt.request(question, context)), mapper)

	private suspend fun generate(body: Map<String, Any?>): GeminiResponse =
		http.post()
			.uri("/v1beta/models/{model}:generateContent", model)
			.bodyValue(body)
			.retrieve()
			.bodyToMono(GeminiResponse::class.java)
			.retryWhen(
				Retry.backoff(MAX_RETRIES, RETRY_BACKOFF)
					.filter { it is WebClientResponseException.TooManyRequests }
					.onRetryExhaustedThrow { _, signal -> signal.failure() },
			)
			.awaitSingle()

	private companion object {
		const val API_KEY_HEADER = "x-goog-api-key"
		const val MAX_CONNECTIONS = 20
		const val MAX_RETRIES = 2L

		val ACQUIRE_TIMEOUT: Duration = Duration.ofSeconds(5)
		val DISPOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
		val RETRY_BACKOFF: Duration = Duration.ofMillis(200)
	}
}
