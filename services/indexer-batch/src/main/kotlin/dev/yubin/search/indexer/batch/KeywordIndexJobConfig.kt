package dev.yubin.search.indexer.batch

import co.elastic.clients.elasticsearch.ElasticsearchClient
import dev.yubin.search.core.analysis.AnalyzerFingerprint
import dev.yubin.search.core.meta.IndexMeta
import dev.yubin.search.core.meta.IndexMetaStore
import dev.yubin.search.core.place.PlaceRow
import dev.yubin.search.indexer.index.CheckpointStore
import dev.yubin.search.indexer.index.EsBulkIndexer
import dev.yubin.search.indexer.index.IndexAdminService
import dev.yubin.search.indexer.index.PlaceRowMapper
import dev.yubin.search.indexer.index.PlaceSql
import org.slf4j.LoggerFactory
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.listener.ChunkListener
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.OffsetDateTime
import javax.sql.DataSource

@Configuration
class KeywordIndexJobConfig(
	private val jobRepository: JobRepository,
	private val transactionManager: PlatformTransactionManager,
	private val dataSource: DataSource,
	private val indexer: EsBulkIndexer,
	private val admin: IndexAdminService,
	private val checkpoints: CheckpointStore,
	private val meta: IndexMetaStore,
	private val es: ElasticsearchClient,
	@Value("\${psp.index.search-alias}") private val searchAlias: String,
	@Value("\${psp.index.suggest-alias}") private val suggestAlias: String,
	@Value("\${psp.index.batch-size}") private val chunkSize: Int,
	@Value("\${psp.index.fetch-size}") private val fetchSize: Int,
	@Value("\${psp.index.keep-versions:2}") private val keepVersions: Int,
) {
	@Bean
	fun keywordRebuildJob(): Job =
		JobBuilder(IndexJobs.KEYWORD_REBUILD, jobRepository)
			.start(keywordRebuildPrepareStep())
			.next(keywordLoadStep())
			.next(keywordRebuildPromoteStep())
			.listener(OrphanIndexCleanupListener(admin, searchAlias, suggestAlias))
			.build()

	@Bean
	fun keywordIncrementalJob(): Job =
		JobBuilder(IndexJobs.KEYWORD_INCREMENTAL, jobRepository)
			.start(keywordIncrementalPrepareStep())
			.next(keywordLoadStep())
			.next(keywordIncrementalPromoteStep())
			.build()

	@Bean
	fun keywordLoadStep(): Step {
		val progress = ChunkProgressLogger("키워드 색인")
		return StepBuilder(IndexJobs.STEP_KEYWORD_LOAD, jobRepository)
			.chunk<PlaceRow, PlaceRow>(chunkSize)
			.transactionManager(transactionManager)
			.reader(keywordPlaceReader())
			.writer(keywordBulkWriter())

			.listener(keywordBulkWriter())
			.listener(progress as ChunkListener<PlaceRow, PlaceRow>)
			.listener(progress as StepExecutionListener)
			.build()
	}

	@Bean
	@StepScope
	fun keywordPlaceReader(
		@Value("#{jobExecutionContext['" + IndexJobs.Ctx.SINCE + "']}") since: String? = null,
	): JdbcCursorItemReader<PlaceRow> {
		val builder = JdbcCursorItemReaderBuilder<PlaceRow>()
			.name("keywordPlaceReader")
			.dataSource(dataSource)
			.rowMapper(PlaceRowMapper)
			.fetchSize(fetchSize)
			.connectionAutoCommit(false)

		return if (since == null) {
			builder.sql(PlaceSql.SELECT_ALL).build()
		} else {
			val watermark = OffsetDateTime.parse(since)
			builder.sql(PlaceSql.SELECT_SINCE)
				.preparedStatementSetter { ps -> ps.setObject(1, watermark) }
				.build()
		}
	}

	@Bean
	@StepScope
	fun keywordBulkWriter(
		@Value("#{jobExecutionContext['" + IndexJobs.Ctx.SEARCH_INDEX + "']}") searchTarget: String? = null,
		@Value("#{jobExecutionContext['" + IndexJobs.Ctx.SUGGEST_INDEX + "']}") suggestTarget: String? = null,
	): KeywordBulkWriter = KeywordBulkWriter(
		indexer,
		requireNotNull(searchTarget) { "prepare step 이 검색 인덱스를 정하지 않았다" },
		requireNotNull(suggestTarget) { "prepare step 이 자동완성 인덱스를 정하지 않았다" },
	)

	@Bean
	fun keywordRebuildPrepareStep(): Step =
		StepBuilder("${IndexJobs.KEYWORD_REBUILD}.${IndexJobs.STEP_PREPARE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val ctx = chunkContext.stepContext.stepExecution.jobExecution.executionContext

				val swept = admin.sweepOrphansAbove(searchAlias) + admin.sweepOrphansAbove(suggestAlias)
				if (swept.isNotEmpty()) log.warn("이전 실행이 남긴 고아 인덱스 {}개 정리 {}", swept.size, swept.sorted())

				val newSearch = admin.createNextVersion(searchAlias, "es/place_search.json")
				val newSuggest = admin.createNextVersion(suggestAlias, "es/place_suggest.json")
				ctx.putString(IndexJobs.Ctx.SEARCH_INDEX, newSearch)
				ctx.putString(IndexJobs.Ctx.SUGGEST_INDEX, newSuggest)

				log.info("키워드 전체 재색인 준비 완료 → {} + {}", newSearch, newSuggest)
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	@Bean
	fun keywordRebuildPromoteStep(): Step =
		StepBuilder("${IndexJobs.KEYWORD_REBUILD}.${IndexJobs.STEP_PROMOTE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val step = chunkContext.stepContext.stepExecution
				val ctx = step.jobExecution.executionContext
				val newSearch = ctx.getString(IndexJobs.Ctx.SEARCH_INDEX)
				val newSuggest = ctx.getString(IndexJobs.Ctx.SUGGEST_INDEX)

				es.indices().refresh { it.index(listOf(newSearch, newSuggest)) }

				admin.swapAlias(searchAlias, newSearch)
				admin.swapAlias(suggestAlias, newSuggest)

				ctx.putString(IndexJobs.Ctx.PROMOTED, "$newSearch,$newSuggest")

				val removed = admin.reconcile(searchAlias, keepVersions) + admin.reconcile(suggestAlias, keepVersions)
				ctx.putString(IndexJobs.Ctx.REMOVED, removed.sorted().joinToString(","))

				meta.write(IndexMeta.PIPELINE_SEARCH, searchStamp(newSearch))
				meta.write(IndexMeta.PIPELINE_SUGGEST, suggestStamp(newSuggest))

				LoadProgress.ofJob(step).maxUpdatedAt?.let {
					checkpoints.set(CheckpointStore.PLACE_PIPELINE, it)
					ctx.putString(IndexJobs.Ctx.CHECKPOINT, it.toString())
				}

				log.info("키워드 전체 재색인 승격 완료 — alias 스왑, {}개 정리 {}", removed.size, removed.sorted())
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	@Bean
	fun keywordIncrementalPrepareStep(): Step =
		StepBuilder("${IndexJobs.KEYWORD_INCREMENTAL}.${IndexJobs.STEP_PREPARE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val ctx = chunkContext.stepContext.stepExecution.jobExecution.executionContext

				admin.indicesBehind(searchAlias).firstOrNull()
					?: error("alias 미설정: $searchAlias — 먼저 전체 재색인이 필요합니다")
				admin.indicesBehind(suggestAlias).firstOrNull()
					?: error("alias 미설정: $suggestAlias")

				meta.requireCompatible(IndexMeta.PIPELINE_SEARCH, searchStamp(searchAlias), remedy = INCREMENTAL_REMEDY)
				meta.requireCompatible(IndexMeta.PIPELINE_SUGGEST, suggestStamp(suggestAlias), remedy = INCREMENTAL_REMEDY)

				ctx.putString(IndexJobs.Ctx.SEARCH_INDEX, searchAlias)
				ctx.putString(IndexJobs.Ctx.SUGGEST_INDEX, suggestAlias)

				val since = checkpoints.get(CheckpointStore.PLACE_PIPELINE) ?: admin.maxUpdatedAt(searchAlias)
				if (since != null) ctx.putString(IndexJobs.Ctx.SINCE, since.toString())

				log.info("키워드 증분 준비 완료 — since={}", since ?: "(없음 → 전체 읽기)")
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	@Bean
	fun keywordIncrementalPromoteStep(): Step =
		StepBuilder("${IndexJobs.KEYWORD_INCREMENTAL}.${IndexJobs.STEP_PROMOTE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val step = chunkContext.stepContext.stepExecution
				val ctx = step.jobExecution.executionContext

				es.indices().refresh { it.index(listOf(searchAlias, suggestAlias)) }

				val since = ctx.getString(IndexJobs.Ctx.SINCE, "").ifEmpty { null }?.let(OffsetDateTime::parse)
				val advanced = LoadProgress.ofJob(step).maxUpdatedAt
					?.takeIf { since == null || it.isAfter(since) }
					?.also { checkpoints.set(CheckpointStore.PLACE_PIPELINE, it) }

				ctx.putString(IndexJobs.Ctx.CHECKPOINT, (advanced ?: since)?.toString() ?: "")
				log.info("키워드 증분 완료 — watermark {} → {}", since, advanced ?: since)
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	private fun searchStamp(index: String) = IndexMeta.stamp(
		analyzerFingerprint = AnalyzerFingerprint.of(es, index, AnalyzerFingerprint.SEARCH_ANALYZER),
	)

	private fun suggestStamp(index: String) = IndexMeta.stamp(
		analyzerFingerprint = AnalyzerFingerprint.of(es, index, AnalyzerFingerprint.SUGGEST_ANALYZER),
	)

	private companion object {
		val log = LoggerFactory.getLogger(KeywordIndexJobConfig::class.java)

		const val INCREMENTAL_REMEDY =
			"POST /admin/reindex 로 전체 재색인하세요. 증분으로는 섞인 인덱스가 됩니다."
	}
}
