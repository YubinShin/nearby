package dev.yubin.search.es

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.transport.ElasticsearchTransport
import dev.yubin.search.debug.EsCapturingTransport
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EsAsyncClientConfig {
	@Bean
	fun elasticsearchAsyncClient(
		transport: ElasticsearchTransport,
		@Value("\${psp.debug.enabled:false}") debug: Boolean,
	): ElasticsearchAsyncClient =
		ElasticsearchAsyncClient(if (debug) EsCapturingTransport(transport) else transport)
}
