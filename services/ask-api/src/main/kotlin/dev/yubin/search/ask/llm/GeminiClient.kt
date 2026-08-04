package dev.yubin.search.ask.llm

import dev.yubin.search.ask.ParsedQuery
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
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
	private val mapper: ObjectMapper,
) : LlmClient {
	init {
		require(apiKey.isNotBlank()) {
			"GEMINI_API_KEY is not set. export it, or start with --psp.ask.llm=fixture to replay recorded responses."
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

	override suspend fun parse(query: String): ParsedQuery {
		val response = http.post()
			.uri("/v1beta/models/{model}:generateContent", model)
			.bodyValue(prompt.request(query))
			.retrieve()
			.awaitBody<GeminiResponse>()
		return GeminiWire.decode(response, mapper)
	}

	private companion object {
		const val API_KEY_HEADER = "x-goog-api-key"
		const val MAX_CONNECTIONS = 20

		val ACQUIRE_TIMEOUT: Duration = Duration.ofSeconds(5)
		val DISPOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
	}
}
