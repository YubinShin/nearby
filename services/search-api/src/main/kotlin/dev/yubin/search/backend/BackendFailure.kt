package dev.yubin.search.backend

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import org.springframework.web.reactive.function.client.WebClientException
import java.io.IOException

object BackendFailure {
	fun causedBy(e: Throwable): Boolean = e is ElasticsearchException || e is IOException || e is WebClientException
}
