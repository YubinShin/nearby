package dev.yubin.search.es

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.transport.ElasticsearchTransport
import dev.yubin.search.debug.EsCapturingTransport
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
class EsAsyncClientConfig {
	@Bean
	fun elasticsearchAsyncClient(
		transport: ElasticsearchTransport,
		json: ObjectMapper,
		@Value("\${psp.debug.enabled:false}") debug: Boolean,
	): ElasticsearchAsyncClient =
		ElasticsearchAsyncClient(if (debug) EsCapturingTransport(transport, json) else transport)
}
