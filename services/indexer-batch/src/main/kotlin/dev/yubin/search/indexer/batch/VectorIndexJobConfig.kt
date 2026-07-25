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

/**
 * 벡터 색인 job 두 개 — **전체 재색인**과 **증분**. (ADR 0013)
 *
 * 키워드 색인([KeywordIndexJobConfig])과 **일부러 따로 돌린다.**
 * - 임베딩 추론이 ES bulk 보다 훨씬 느리다(키워드 17초 vs 벡터 8분 33초). 한 파이프라인에 묶으면
 *   느린 쪽이 빠른 쪽의 발목을 잡는다.
 * - 모델을 바꾸면 벡터만 전부 다시 만들어야 한다. 그때 키워드 인덱스까지 재색인할 이유가 없다.
 *
 * 그래서 job 도 체크포인트도 따로다 ([CheckpointStore.PLACE_VECTOR]). 구조는 키워드와 같은
 * prepare → load → promote 세 단계이고, 공유하는 적재 step 하나로 전체·증분을 모두 처리한다.
 *
 * ### 여기서 Spring Batch 가 가장 크게 값을 한다
 * 벡터 전체 재색인은 실측 8분 33초(kind 환경 32분)다. 전에는 이게 **HTTP 요청 하나의 수명**에
 * 매달려 있어서 `curl` 을 끊으면 색인이 죽었고, 취소 경로에서 r2dbc 커넥션 누수(`DataRow.release()`
 * 누락)까지 냈다. 지금은 job 스레드에서 돌고 요청은 즉시 202 로 끝난다 — 진행 상황은
 * `GET /admin/jobs/{id}` 로 따로 본다.
 */
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
	@Value("\${psp.index.keep-versions:2}") private val keepVersions: Int,
) {

	// ---- job ----

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

	// ---- 적재 step (두 job 이 공유) ----

	/** 구조는 키워드 적재 step 과 같다 — 새 `ChunkOrientedStep` 경로. 이유는 [KeywordIndexJobConfig]. */
	@Bean
	fun vectorLoadStep(): Step =
		StepBuilder(IndexJobs.STEP_VECTOR_LOAD, jobRepository)
			.chunk<PlaceRow, PlaceRow>(chunkSize)
			.transactionManager(transactionManager)
			.reader(vectorPlaceReader())
			.writer(vectorUpsertWriter())
			.listener(vectorUpsertWriter())
			.listener(ChunkProgressLogger("벡터 색인"))
			.build()

	/** 키워드 리더와 같은 규칙 — `since` 가 있으면 델타, 없으면 전체. 이유는 [KeywordIndexJobConfig]. */
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
		requireNotNull(target) { "prepare step 이 대상 컬렉션을 정하지 않았다" },
		embedBatch,
	)

	// ---- 전체 재색인: 준비 / 승격 ----

	@Bean
	fun vectorRebuildPrepareStep(): Step =
		StepBuilder("${IndexJobs.VECTOR_REBUILD}.${IndexJobs.STEP_PREPARE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val ctx = chunkContext.stepContext.stepExecution.jobExecution.executionContext
				val newCollection = qdrant.createNextVersion(alias, embeddings.dimension)
				ctx.putString(IndexJobs.Ctx.COLLECTION, newCollection)
				log.info("벡터 전체 재색인 준비 완료 → {} ({}차원)", newCollection, embeddings.dimension)
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
				val removed = qdrant.reconcile(alias, keepVersions)
				ctx.putString(IndexJobs.Ctx.REMOVED, removed.sorted().joinToString(","))

				/*
				 * 스왑 성공 후 버전 도장 (ADR 0011). 여기 남기는 값은 설정이 아니라 **실제로 로드된
				 * 모델**이다 — 설정만 맞고 파일이 다른 경우까지 잡으려는 것. 질의기가 기동할 때
				 * 자기 모델과 대조하고, 다르면 뜨지 않는다.
				 */
				meta.write(
					IndexMeta.PIPELINE_VECTOR,
					IndexMeta.stamp(embeddingModel = embeddings.modelId, embeddingDim = embeddings.dimension),
				)

				LoadProgress.ofJob(step).maxUpdatedAt?.let {
					checkpoints.set(CheckpointStore.PLACE_VECTOR, it)
					ctx.putString(IndexJobs.Ctx.CHECKPOINT, it.toString())
				}

				log.info(
					"벡터 전체 재색인 승격 완료 — {} ({}점), {}개 정리 {}",
					newCollection, qdrant.count(newCollection), removed.size, removed.sorted(),
				)
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	// ---- 증분: 준비 / 승격 ----

	@Bean
	fun vectorIncrementalPrepareStep(): Step =
		StepBuilder("${IndexJobs.VECTOR_INCREMENTAL}.${IndexJobs.STEP_PREPARE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val ctx = chunkContext.stepContext.stepExecution.jobExecution.executionContext

				qdrant.collectionsBehind(alias).firstOrNull()
					?: error("alias 미설정: $alias — 먼저 벡터 전체 재색인이 필요합니다")

				/*
				 * **증분은 살아있는 컬렉션에 그대로 덮어쓴다.** 그래서 모델이 바뀐 채로 증분을 돌리면
				 * 한 컬렉션 안에 옛 모델 벡터와 새 모델 벡터가 섞인다 — 그리고 그 상태는 오류를 내지
				 * 않는다. 384차원끼리라면 유사도 계산은 멀쩡히 되고, 숫자만 의미를 잃는다.
				 *
				 * 섞인 걸 나중에 감지하는 것보다 **애초에 못 섞이게** 막는 쪽이 싸다 (ADR 0011).
				 * 모델을 바꿨으면 답은 하나뿐이다 — 전체 재색인.
				 */
				meta.requireCompatible(
					IndexMeta.PIPELINE_VECTOR,
					IndexMeta.stamp(embeddingModel = embeddings.modelId, embeddingDim = embeddings.dimension),
					remedy = "POST /admin/vector/rebuild 로 전체 재색인하세요. 증분으로는 섞인 컬렉션이 됩니다.",
				)

				ctx.putString(IndexJobs.Ctx.COLLECTION, alias)

				val since = checkpoints.get(CheckpointStore.PLACE_VECTOR)
				if (since != null) ctx.putString(IndexJobs.Ctx.SINCE, since.toString())

				log.info("벡터 증분 준비 완료 — since={}", since ?: "(없음 → 전체 읽기)")
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
				log.info("벡터 증분 완료 — watermark {} → {}", since, advanced ?: since)
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	private companion object {
		val log = LoggerFactory.getLogger(VectorIndexJobConfig::class.java)
	}
}
