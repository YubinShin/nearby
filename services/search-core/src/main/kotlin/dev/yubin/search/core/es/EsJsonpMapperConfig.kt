package dev.yubin.search.core.es

import co.elastic.clients.json.JsonpMapper
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EsJsonpMapperConfig {
	@Bean
	fun jsonpMapper(): JsonpMapper = JacksonJsonpMapper(
		ObjectMapper()
			.registerModule(KotlinModule.Builder().build())
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
	)
}
