package dev.yubin.search.es

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.transport.ElasticsearchTransport
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EsAsyncClientConfig {
	@Bean
	fun elasticsearchAsyncClient(transport: ElasticsearchTransport): ElasticsearchAsyncClient =
		ElasticsearchAsyncClient(transport)
}
