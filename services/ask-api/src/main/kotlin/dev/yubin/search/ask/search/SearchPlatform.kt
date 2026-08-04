package dev.yubin.search.ask.search

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import dev.yubin.search.ask.SearchRequestPlan
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration

data class SearchResult(val body: JsonNode, val degraded: Boolean, val total: Long)

interface SearchPlatform {
	suspend fun hsearch(plan: SearchRequestPlan): SearchResult
}

@Component
class SearchApiClient(
	@Value("\${psp.ask.search.base-url}") baseUrl: String,
	@Value("\${psp.ask.search.timeout-ms}") timeoutMs: Long,
	private val mapper: ObjectMapper,
) : SearchPlatform {
	private val connections = ConnectionProvider.builder("search-api")
		.maxConnections(MAX_CONNECTIONS)
		.pendingAcquireTimeout(ACQUIRE_TIMEOUT)
		.build()

	private val http = WebClient.builder()
		.baseUrl(baseUrl)
		.clientConnector(
			ReactorClientHttpConnector(
				HttpClient.create(connections).responseTimeout(Duration.ofMillis(timeoutMs)),
			),
		)
		.codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES) }
		.build()

	@PreDestroy
	fun close() {
		connections.disposeLater().block(DISPOSE_TIMEOUT)
	}

	override suspend fun hsearch(plan: SearchRequestPlan): SearchResult {
		val body = http.get()
			.uri { builder ->
				builder.path(HSEARCH)
					.queryParam("q", plan.q)
					.queryParam("size", plan.size)
					.apply {
						plan.lat?.let { queryParam("lat", it) }
						plan.lon?.let { queryParam("lon", it) }
						plan.radius?.let { queryParam("radius", it) }
					}
					.build()
			}
			.retrieve()
			.awaitBody<JsonNode>()

		val meta = mapper.treeToValue(body, HsearchMeta::class.java)
		return SearchResult(body, meta.degraded, meta.total)
	}

	private companion object {
		const val HSEARCH = "/v1/hsearch"
		const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
		const val MAX_CONNECTIONS = 50

		val ACQUIRE_TIMEOUT: Duration = Duration.ofSeconds(5)
		val DISPOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
	}
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class HsearchMeta(val degraded: Boolean = false, val total: Long = 0)
