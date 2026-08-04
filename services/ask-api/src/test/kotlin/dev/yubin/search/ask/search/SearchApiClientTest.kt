package dev.yubin.search.ask.search

import dev.yubin.search.ask.SearchRequestPlan
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import tools.jackson.databind.ObjectMapper
import java.net.URLDecoder
import kotlin.test.assertEquals

@SpringBootTest(properties = ["psp.ask.llm=fixture"])
class SearchApiClientTest @Autowired constructor(private val mapper: ObjectMapper) {
	@Volatile
	private var received: String? = null

	private val server = HttpServer.create().port(0)
		.handle { request, response ->
			received = request.uri()
			response.header("Content-Type", "application/json")
				.sendString(Mono.just("""{"total":3,"degraded":false,"channels":[],"hits":[]}"""))
		}
		.bindNow()

	@AfterEach
	fun stop() {
		server.disposeNow()
	}

	@Test
	fun `a query holding braces reaches the platform instead of failing uri expansion`() = runTest {
		val result = client().hsearch(SearchRequestPlan(q = "파스타 {강남}", size = 10))

		assertEquals(3, result.total)
		assertEquals("파스타 {강남}", queryParam("q"))
	}

	@Test
	fun `the geo parameters ride along exactly as planned`() = runTest {
		client().hsearch(SearchRequestPlan(q = "편의점", size = 10, lat = 37.4979, lon = 127.0276, radius = 500))

		assertEquals("편의점", queryParam("q"))
		assertEquals("10", queryParam("size"))
		assertEquals("37.4979", queryParam("lat"))
		assertEquals("127.0276", queryParam("lon"))
		assertEquals("500", queryParam("radius"))
	}

	private fun client() = SearchApiClient("http://127.0.0.1:${server.port()}", 5_000, mapper)

	private fun queryParam(name: String): String? =
		received?.substringAfter('?', "")
			?.split("&")
			?.map { it.split("=", limit = 2) }
			?.firstOrNull { it.first() == name }
			?.let { URLDecoder.decode(it.getOrElse(1) { "" }, Charsets.UTF_8) }
}
