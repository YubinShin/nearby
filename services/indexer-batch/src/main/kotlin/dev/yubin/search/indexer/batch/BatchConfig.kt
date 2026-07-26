package dev.yubin.search.indexer.batch

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import javax.sql.DataSource

@Configuration
class BatchConfig(private val dataSource: DataSource) : JdbcDefaultBatchConfiguration(), DisposableBean {
	private val jobExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 8
		setThreadNamePrefix("index-job-")

		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS)
		initialize()
	}

	override fun getTaskExecutor(): TaskExecutor = jobExecutor

	override fun getDataSource(): DataSource {
		BatchSchema.ensure(dataSource)
		return dataSource
	}

	override fun destroy() {
		jobExecutor.shutdown()
	}

	private companion object {
		const val AWAIT_TERMINATION_SECONDS = 600
	}
}
