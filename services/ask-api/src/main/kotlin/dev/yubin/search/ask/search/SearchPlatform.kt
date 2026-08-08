package dev.yubin.search.ask.search

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import dev.yubin.search.ask.SearchRequestPlan
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
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

data class SearchResult(
	val body: JsonNode,
	val degraded: Boolean,
	val total: Long,
	val records: List<PlaceRecord> = emptyList(),
	val unrenderable: Int = 0,
	val absentFields: List<String> = emptyList(),
)

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
					.queryParam("q", "{q}")
					.queryParam("size", plan.size)
					.apply {
						plan.lat?.let { queryParam("lat", it) }
						plan.lon?.let { queryParam("lon", it) }
						plan.radius?.let { queryParam("radius", it) }
					}
					.build(mapOf("q" to plan.q))
			}
			.retrieve()
			.awaitBody<JsonNode>()

		val meta = mapper.treeToValue(body, HsearchMeta::class.java)
		val decoded = HsearchContract.decode(body, mapper)
		if (decoded.unrenderable > 0) {
			log.warn(
				"{} of {} hits carry no {} — the answer stage cannot cite them",
				decoded.unrenderable,
				decoded.unrenderable + decoded.records.size,
				HsearchContract.REQUIRED.joinToString(" or "),
			)
		}
		if (decoded.absentRequired.isNotEmpty()) {
			log.error(
				"no hit carries {} — search-api changed the hsearch response and this app still expects the old one. " +
					"the decoder falls back instead of failing, so answers lose the field without an error.",
				decoded.absentRequired,
			)
		}
		if (decoded.absentOptional.isNotEmpty()) {
			log.warn("no hit carries {} — the answer renders a hole where the field would go", decoded.absentOptional)
		}
		return SearchResult(body, meta.degraded, meta.total, decoded.records, decoded.unrenderable, decoded.absentFields)
	}

	private companion object {
		const val HSEARCH = "/v1/hsearch"
		const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
		const val MAX_CONNECTIONS = 50

		val ACQUIRE_TIMEOUT: Duration = Duration.ofSeconds(5)
		val DISPOSE_TIMEOUT: Duration = Duration.ofSeconds(5)

		val log = LoggerFactory.getLogger(SearchApiClient::class.java)
	}
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class HsearchMeta(val degraded: Boolean = false, val total: Long = 0)
