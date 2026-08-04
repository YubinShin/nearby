package dev.yubin.search.upstream

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.json.JsonpMappingException
import jakarta.json.JsonException
import org.springframework.core.codec.DecodingException
import org.springframework.web.reactive.function.client.WebClientException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException

enum class Upstream(val wire: String) {
	ELASTICSEARCH("elasticsearch"),
	QDRANT("qdrant"),
}

object UpstreamFailure {
	fun of(e: Throwable): Upstream? =
		generateSequence(e) { prev -> prev.cause?.takeIf { it !== prev } }
			.take(MAX_CAUSE_DEPTH)
			.firstNotNullOfOrNull { classify(it) }

	private fun classify(e: Throwable): Upstream? = when (e) {
		is JsonpMappingException -> null
		is ElasticsearchException -> Upstream.ELASTICSEARCH.takeIf { unavailable(e.status()) }
		is WebClientResponseException -> Upstream.QDRANT.takeIf { unavailable(e.statusCode.value()) }
		is WebClientException -> Upstream.QDRANT
		is DecodingException -> Upstream.QDRANT
		is JsonException -> Upstream.ELASTICSEARCH
		is IOException -> Upstream.ELASTICSEARCH
		else -> null
	}

	private fun unavailable(status: Int) = status >= 500 || status == 429 || status == 404

	private const val MAX_CAUSE_DEPTH = 10
}