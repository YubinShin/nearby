package dev.yubin.search.indexer.batch

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

object BatchSchema {
	private val log = LoggerFactory.getLogger(BatchSchema::class.java)

	private val done = AtomicBoolean(false)

	private const val SCRIPT = "org/springframework/batch/core/schema-postgresql.sql"

	private const val SENTINEL = "BATCH_JOB_INSTANCE"

	fun ensure(dataSource: DataSource) {
		if (!done.compareAndSet(false, true)) return

		if (exists(dataSource)) {
			log.debug("Batch 메타데이터 테이블 확인됨 — 초기화 건너뜀")
			return
		}

		log.info("Batch 메타데이터 테이블이 없어 생성한다 ({})", SCRIPT)
		ResourceDatabasePopulator(ClassPathResource(SCRIPT)).execute(dataSource)
	}

	private fun exists(dataSource: DataSource): Boolean =
		dataSource.connection.use { connection ->
			val meta = connection.metaData
			listOf(SENTINEL, SENTINEL.lowercase()).any { name ->
				meta.getTables(null, null, name, arrayOf("TABLE")).use { it.next() }
			}
		}
}
