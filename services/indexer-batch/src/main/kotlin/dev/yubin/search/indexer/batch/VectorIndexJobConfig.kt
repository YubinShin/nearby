package dev.yubin.search.indexer.batch

import dev.yubin.search.core.embed.EmbeddingModel
import dev.yubin.search.core.meta.IndexMeta
import dev.yubin.search.core.meta.IndexMetaStore
import dev.yubin.search.core.place.PlaceRow
import dev.yubin.search.indexer.index.CheckpointStore
import dev.yubin.search.indexer.index.PlaceRowMapper
import dev.yubin.search.indexer.index.PlaceSql
import dev.yubin.search.indexer.vector.QdrantIndexStore
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.OffsetDateTime
import javax.sql.DataSource

@Configuration
@ConditionalOnProperty(
	name = ["psp.vector.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class VectorIndexJobConfig(
	private val jobRepository: JobRepository,
	private val transactionManager: PlatformTransactionManager,
	private val dataSource: DataSource,
	private val embeddings: EmbeddingModel,
	private val qdrant: QdrantIndexStore,
	private val checkpoints: CheckpointStore,
	private val meta: IndexMetaStore,
	@Value("\${psp.vector.alias}") private val alias: String,
	@Value("\${psp.vector.batch-size}") private val chunkSize: Int,
	@Value("\${psp.embedding.batch-size}") private val embedBatch: Int,
	@Value("\${psp.index.fetch-size}") private val fetchSize: Int,
	@Value("\${psp.index.keep-versions}") private val keepVersions: Int,
) {
	@Bean
	fun vectorRebuildJob(): Job =
		JobBuilder(IndexJobs.VECTOR_REBUILD, jobRepository)
			.start(vectorRebuildPrepareStep())
			.next(vectorLoadStep())
			.next(vectorRebuildPromoteStep())
			.listener(OrphanCollectionCleanupListener(qdrant, alias))
			.build()

	@Bean
	fun vectorIncrementalJob(): Job =
		JobBuilder(IndexJobs.VECTOR_INCREMENTAL, jobRepository)
			.start(vectorIncrementalPrepareStep())
			.next(vectorLoadStep())
			.next(vectorIncrementalPromoteStep())
			.build()

	@Bean
	fun vectorLoadStep(): Step {
		val progress = ChunkProgressLogger("vector indexing")
		return StepBuilder(IndexJobs.STEP_VECTOR_LOAD, jobRepository)
			.chunk<PlaceRow, PlaceRow>(chunkSize)
			.transactionManager(transactionManager)
			.reader(vectorPlaceReader())
			.writer(vectorUpsertWriter())
			.listener(vectorUpsertWriter())
			.listener(progress as ChunkListener<PlaceRow, PlaceRow>)
			.listener(progress as StepExecutionListener)
			.build()
	}

	@Bean
	@StepScope
	fun vectorPlaceReader(
		@Value("#{jobExecutionContext['" + IndexJobs.Ctx.SINCE + "']}") since: String? = null,
	): JdbcCursorItemReader<PlaceRow> {
		val builder = JdbcCursorItemReaderBuilder<PlaceRow>()
			.name("vectorPlaceReader")
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
	fun vectorUpsertWriter(
		@Value("#{jobExecutionContext['" + IndexJobs.Ctx.COLLECTION + "']}") target: String? = null,
	): VectorUpsertWriter = VectorUpsertWriter(
		embeddings,
		qdrant,
		requireNotNull(target) { "the prepare step did not choose a target collection" },
		embedBatch,
	)

	@Bean
	fun vectorRebuildPrepareStep(): Step =
		StepBuilder("${IndexJobs.VECTOR_REBUILD}.${IndexJobs.STEP_PREPARE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val ctx = chunkContext.stepContext.stepExecution.jobExecution.executionContext

				val swept = qdrant.sweepOrphansAbove(alias)
				if (swept.isNotEmpty()) log.warn("swept {} orphan collections left by a previous run {}", swept.size, swept.sorted())

				val newCollection = qdrant.createNextVersion(alias, embeddings.dimension)
				ctx.putString(IndexJobs.Ctx.COLLECTION, newCollection)
				log.info("vector full reindex prepared → {} ({} dims)", newCollection, embeddings.dimension)
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	@Bean
	fun vectorRebuildPromoteStep(): Step =
		StepBuilder("${IndexJobs.VECTOR_REBUILD}.${IndexJobs.STEP_PROMOTE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val step = chunkContext.stepContext.stepExecution
				val ctx = step.jobExecution.executionContext
				val newCollection = ctx.getString(IndexJobs.Ctx.COLLECTION)

				qdrant.swapAlias(alias, newCollection)

				ctx.putString(IndexJobs.Ctx.PROMOTED, newCollection)

				val removed = qdrant.reconcile(alias, keepVersions)
				ctx.putString(IndexJobs.Ctx.REMOVED, removed.sorted().joinToString(","))

				meta.write(
					IndexMeta.PIPELINE_VECTOR,
					IndexMeta.stamp(embeddingModel = embeddings.modelId, embeddingDim = embeddings.dimension),
				)

				LoadProgress.ofJob(step).maxUpdatedAt?.let {
					checkpoints.set(CheckpointStore.PLACE_VECTOR, it)
					ctx.putString(IndexJobs.Ctx.CHECKPOINT, it.toString())
				}

				log.info(
					"vector full reindex promoted — {} ({} points), swept {} {}",
					newCollection, qdrant.count(newCollection), removed.size, removed.sorted(),
				)
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	@Bean
	fun vectorIncrementalPrepareStep(): Step =
		StepBuilder("${IndexJobs.VECTOR_INCREMENTAL}.${IndexJobs.STEP_PREPARE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val ctx = chunkContext.stepContext.stepExecution.jobExecution.executionContext

				qdrant.collectionsBehind(alias).firstOrNull()
					?: error("alias not set: $alias — run a full vector reindex first")

				meta.requireCompatible(
					IndexMeta.PIPELINE_VECTOR,
					IndexMeta.stamp(embeddingModel = embeddings.modelId, embeddingDim = embeddings.dimension),
					remedy = INCREMENTAL_REMEDY,
				)

				ctx.putString(IndexJobs.Ctx.COLLECTION, alias)

				val since = checkpoints.get(CheckpointStore.PLACE_VECTOR)
				if (since != null) ctx.putString(IndexJobs.Ctx.SINCE, since.toString())

				log.info("vector incremental prepared — since={}", since ?: "(none → full read)")
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	@Bean
	fun vectorIncrementalPromoteStep(): Step =
		StepBuilder("${IndexJobs.VECTOR_INCREMENTAL}.${IndexJobs.STEP_PROMOTE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val step = chunkContext.stepContext.stepExecution
				val ctx = step.jobExecution.executionContext

				val since = ctx.getString(IndexJobs.Ctx.SINCE, "").ifEmpty { null }?.let(OffsetDateTime::parse)
				val advanced = LoadProgress.ofJob(step).maxUpdatedAt
					?.takeIf { since == null || it.isAfter(since) }
					?.also { checkpoints.set(CheckpointStore.PLACE_VECTOR, it) }

				ctx.putString(IndexJobs.Ctx.CHECKPOINT, (advanced ?: since)?.toString() ?: "")
				log.info("vector incremental done — watermark {} → {}", since, advanced ?: since)
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	private companion object {
		val log = LoggerFactory.getLogger(VectorIndexJobConfig::class.java)

		const val INCREMENTAL_REMEDY =
			"run a full reindex with POST /admin/vector/reindex. an incremental run would leave the collection mixed."
	}
}
