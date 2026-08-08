package dev.yubin.search.indexer.admin

import dev.yubin.search.indexer.batch.IndexJobService
import dev.yubin.search.indexer.batch.IndexJobs
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanupGuardTest {
	private val jobs = mock(IndexJobService::class.java)

	@Test
	fun `cleanup is refused while a rebuild is loading into a new index`() {
		doReturn(1).`when`(jobs).runningCount(IndexJobs.KEYWORD_REBUILD)

		val e = assertFailsWithConflict { rejectWhileRebuilding(jobs, IndexJobs.KEYWORD_REBUILD) }

		assertTrue(IndexJobs.KEYWORD_REBUILD in e.reason.orEmpty(), "the message names the job: ${e.reason}")
	}

	@Test
	fun `cleanup runs when no rebuild is in flight`() {
		doReturn(0).`when`(jobs).runningCount(IndexJobs.VECTOR_REBUILD)

		rejectWhileRebuilding(jobs, IndexJobs.VECTOR_REBUILD)
	}

	private fun assertFailsWithConflict(block: () -> Unit): ResponseStatusException {
		val e = runCatching(block).exceptionOrNull()
		assertTrue(e is ResponseStatusException, "expected ResponseStatusException, got $e")
		assertEquals(HttpStatus.CONFLICT, e.statusCode)
		return e
	}
}
