package dev.yubin.search.backend

import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendFailureTest {
	@Test
	fun `ES IO 예외는 백엔드 실패로 판정한다`() {
		assertTrue(BackendFailure.causedBy(IOException("connection refused")))
	}

	@Test
	fun `Qdrant WebClient 예외는 백엔드 실패로 판정한다`() {
		val e = WebClientResponseException.create(503, "Service Unavailable", HttpHeaders.EMPTY, ByteArray(0), null)

		assertTrue(BackendFailure.causedBy(e))
	}

	@Test
	fun `백엔드와 무관한 예외(버그)는 실패로 판정하지 않는다`() {
		assertFalse(BackendFailure.causedBy(IllegalStateException("null pointer 같은 진짜 버그")))
	}
}
