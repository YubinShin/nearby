package dev.yubin.search.indexer

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import kotlin.test.assertTrue

@Tag("infra")
@SpringBootTest
@AutoConfigureMockMvc
class PrometheusScrapeTest @Autowired constructor(private val mvc: MockMvc) {
	@Test
	fun `the exposed prometheus endpoint actually serves the registry`() {
		val response = mvc.perform(get("/actuator/prometheus")).andReturn().response

		assertTrue(response.status == 200, "status=${response.status}")
		assertTrue("""application="indexer-batch"""" in response.contentAsString, response.contentAsString.take(400))
	}
}
