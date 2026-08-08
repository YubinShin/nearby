package dev.yubin.search.indexer.batch

import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchConfigShutdownTest {
	private fun singleThreadExecutor() = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 8
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(WAIT_SECONDS.toInt())
		initialize()
	}

	@Test
	fun `queued jobs are dropped so shutdown does not start them`() {
		val executor = singleThreadExecutor()
		val running = CountDownLatch(1)
		val release = CountDownLatch(1)
		val queuedStarts = AtomicInteger()

		executor.execute {
			running.countDown()
			release.await(WAIT_SECONDS, TimeUnit.SECONDS)
		}
		assertTrue(running.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first task never started")
		repeat(3) { executor.execute { queuedStarts.incrementAndGet() } }

		assertEquals(3, dropQueuedJobs(executor))

		release.countDown()
		executor.shutdown()

		assertEquals(0, queuedStarts.get(), "queued jobs must not run after the queue is drained")
	}

	@Test
	fun `an empty queue drops nothing`() {
		val executor = singleThreadExecutor()

		assertEquals(0, dropQueuedJobs(executor))

		executor.shutdown()
	}

	@Test
	fun `the job pool runs one job at a time so a second request waits`() {
		val executor = singleThreadExecutor()
		val running = CountDownLatch(1)
		val release = CountDownLatch(1)
		val secondStarted = CountDownLatch(1)

		executor.execute {
			running.countDown()
			release.await(WAIT_SECONDS, TimeUnit.SECONDS)
		}
		assertTrue(running.await(WAIT_SECONDS, TimeUnit.SECONDS))
		executor.execute { secondStarted.countDown() }

		assertEquals(1, secondStarted.count, "the second job must wait for the first")

		release.countDown()
		assertTrue(secondStarted.await(WAIT_SECONDS, TimeUnit.SECONDS), "the second job never ran")
		executor.shutdown()
	}

	private companion object {
		const val WAIT_SECONDS = 5L
	}
}
