package dev.yubin.search.backend

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.json.JsonpMappingException
import jakarta.json.JsonException
import org.springframework.core.codec.DecodingException
import org.springframework.web.reactive.function.client.WebClientException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException

object BackendFailure {
	const val ELASTICSEARCH = "elasticsearch"
	const val QDRANT = "qdrant"

	fun causedBy(e: Throwable): Boolean = backendOf(e) != null

	fun backendOf(e: Throwable): String? =
		generateSequence(e) { prev -> prev.cause?.takeIf { it !== prev } }
			.take(MAX_CAUSE_DEPTH)
			.firstNotNullOfOrNull { classify(it) }

	private fun classify(e: Throwable): String? = when (e) {
		is JsonpMappingException -> null
		is ElasticsearchException -> ELASTICSEARCH.takeIf { unavailable(e.status()) }
		is WebClientResponseException -> QDRANT.takeIf { unavailable(e.statusCode.value()) }
		is WebClientException -> QDRANT
		is DecodingException -> QDRANT
		is JsonException -> ELASTICSEARCH
		is IOException -> ELASTICSEARCH
		else -> null
	}

	private fun unavailable(status: Int) = status >= 500 || status == 429 || status == 404

	private const val MAX_CAUSE_DEPTH = 10
}
