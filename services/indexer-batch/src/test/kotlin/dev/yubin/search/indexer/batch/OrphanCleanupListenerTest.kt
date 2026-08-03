package dev.yubin.search.indexer.batch

import dev.yubin.search.indexer.index.IndexAdminService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.JobInstance
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.step.StepExecution

class OrphanCleanupListenerTest {
	private val searchAlias = "place_search"
	private val suggestAlias = "place_suggest"
	private val newSearch = "place_search_20260803000000"
	private val newSuggest = "place_suggest_20260803000001"

	private fun failedJob(
		loadStatus: BatchStatus? = null,
		promoted: String? = null,
	): JobExecution {
		val job = JobExecution(
			1L,
			JobInstance(1L, IndexJobs.KEYWORD_REBUILD),
			JobParameters(),
		)
		job.status = BatchStatus.FAILED
		job.executionContext.putString(IndexJobs.Ctx.SEARCH_INDEX, newSearch)
		job.executionContext.putString(IndexJobs.Ctx.SUGGEST_INDEX, newSuggest)
		promoted?.let { job.executionContext.putString(IndexJobs.Ctx.PROMOTED, it) }
		loadStatus?.let {
			job.addStepExecution(StepExecution(1L, IndexJobs.STEP_KEYWORD_LOAD, job).apply { status = it })
		}
		return job
	}

	private fun listener(admin: IndexAdminService) =
		OrphanIndexCleanupListener(admin, searchAlias, suggestAlias)

	@Test
	fun `a job that did not finish loading has its half-built indices removed`() {
		val admin = mock(IndexAdminService::class.java)
		doReturn(emptySet<String>()).`when`(admin).indicesBehind(searchAlias)
		doReturn(emptySet<String>()).`when`(admin).indicesBehind(suggestAlias)

		listener(admin).afterJob(failedJob(loadStatus = BatchStatus.FAILED))

		verify(admin).deleteIndices(setOf(newSearch, newSuggest))
	}

	@Test
	fun `an index already behind an alias is never deleted`() {
		val admin = mock(IndexAdminService::class.java)
		doReturn(setOf(newSearch)).`when`(admin).indicesBehind(searchAlias)
		doReturn(emptySet<String>()).`when`(admin).indicesBehind(suggestAlias)

		listener(admin).afterJob(failedJob(loadStatus = BatchStatus.FAILED))

		verify(admin).deleteIndices(setOf(newSuggest))
	}

	@Test
	fun `a completed load is not thrown away when promotion fails`() {
		val admin = mock(IndexAdminService::class.java)

		listener(admin).afterJob(failedJob(loadStatus = BatchStatus.COMPLETED))

		verify(admin, never()).deleteIndices(anySet())
	}

	@Test
	fun `nothing is deleted when the alias lookup fails`() {
		val admin = mock(IndexAdminService::class.java)
		doThrow(RuntimeException("es down")).`when`(admin).indicesBehind(searchAlias)

		listener(admin).afterJob(failedJob(loadStatus = BatchStatus.FAILED))

		verify(admin, never()).deleteIndices(anySet())
	}

	@Test
	fun `a job that failed after promotion is left alone`() {
		val admin = mock(IndexAdminService::class.java)

		listener(admin).afterJob(failedJob(promoted = "$newSearch,$newSuggest"))

		verify(admin, never()).deleteIndices(anySet())
	}

	@Test
	fun `a successful job is left alone`() {
		val admin = mock(IndexAdminService::class.java)
		val job = failedJob(loadStatus = BatchStatus.COMPLETED)
		job.status = BatchStatus.COMPLETED

		listener(admin).afterJob(job)

		verify(admin, never()).deleteIndices(anySet())
	}

	private fun anySet(): Set<String> = org.mockito.ArgumentMatchers.anySet()
}
