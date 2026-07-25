package dev.yubin.search.indexer.batch

import co.elastic.clients.elasticsearch.ElasticsearchClient
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

/**
 * 키워드 색인 job 두 개 — **전체 재색인**과 **증분**. (ADR 0013)
 *
 * ### 전에는 무엇이었나
 * `ReindexService` 라는 클래스 하나에 `rebuildAndSwap()` 과 `incremental()` 두 개의 `suspend`
 * 함수가 있었고, 그 안에 `Flow` 를 모아 배치로 자르는 루프(`indexFlow`)를 직접 갖고 있었다.
 * 지금 그 루프는 **chunk** 다. 내가 쓴 코드에서 사라진 것들:
 * - 배치 크기만큼 모으는 `ArrayList` 와 `flush()` — chunk 크기 설정이 대신한다
 * - 건수 세기 — `BATCH_STEP_EXECUTION.READ_COUNT` 가 대신한다
 * - "어디까지 했나" 추적 — `ExecutionContext` 가 대신한다
 * - try/catch 로 고아 인덱스 정리 — `JobExecutionListener` 가 대신한다
 *
 * ### 두 job 이 적재 step 을 공유한다
 * 전체와 증분의 차이는 **무엇을 읽고 어디에 쓰는가**뿐이다. 그 두 개를 prepare step 이 정해
 * `ExecutionContext` 에 넣어주면, 적재 step 은 같은 것을 쓸 수 있다.
 * - 전체: 새 버전 인덱스에 쓰고, `since` 를 안 넣는다 → 리더가 전체를 읽는다
 * - 증분: alias 에 바로 쓰고, `since` 에 watermark 를 넣는다 → 리더가 델타만 읽는다
 *
 * 증분인데 체크포인트가 없으면(첫 실행) `since` 가 비고 전체를 읽는다 — 전 코드의
 * "증분(최초=전체)"와 같은 동작이다.
 *
 * ### chunk 트랜잭션이 지키는 것과 못 지키는 것
 * chunk 하나는 트랜잭션 하나다. 그런데 그 트랜잭션이 감싸는 건 **Postgres 의 Batch 메타데이터**
 * (읽은 위치·건수·컨텍스트)이고, ES 쓰기는 그 트랜잭션 밖이다 — ES 에 롤백이 없기 때문이다.
 * 그래서 chunk 가 실패하면 "메타데이터는 되돌아갔는데 ES 에는 일부 쓰인" 상태가 될 수 있다.
 *
 * **이게 안전한 이유는 색인이 멱등이기 때문이다** (ADR 0001). 같은 행을 다시 쓰면 덮어쓰기고, 이미
 * 지운 문서를 또 지우면 404 를 정상으로 처리한다([EsBulkIndexer]). 되돌리는 대신 **다시 해도
 * 같아지게** 만들어 두는 쪽을 골랐다.
 */
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

	// ---- job ----

	/**
	 * 전체 재색인: 새 버전 두 개 생성 → 적재 → alias 원자 스왑 → 옛 버전 정리 (무중단, ADR 0002).
	 *
	 * 실패하면 [OrphanIndexCleanupListener] 가 방금 만든 인덱스를 지운다 — 스왑 전이라 서빙에는
	 * 영향이 없고, 정리만 하면 흔적이 남지 않는다.
	 */
	@Bean
	fun keywordRebuildJob(): Job =
		JobBuilder(IndexJobs.KEYWORD_REBUILD, jobRepository)
			.start(keywordRebuildPrepareStep())
			.next(keywordLoadStep())
			.next(keywordRebuildPromoteStep())
			.listener(OrphanIndexCleanupListener(admin, searchAlias, suggestAlias))
			.build()

	/** 증분: 체크포인트 이후 바뀐 행만 현재 alias 인덱스에 upsert/delete (멱등, ADR 0001). */
	@Bean
	fun keywordIncrementalJob(): Job =
		JobBuilder(IndexJobs.KEYWORD_INCREMENTAL, jobRepository)
			.start(keywordIncrementalPrepareStep())
			.next(keywordLoadStep())
			.next(keywordIncrementalPromoteStep())
			.build()

	// ---- 적재 step (두 job 이 공유) ----

	/**
	 * 원천을 커서로 흘려보내며 chunk 단위로 ES 에 적용한다.
	 *
	 * `chunk(size)` + `transactionManager(...)` 는 Batch 6 의 새 `ChunkOrientedStep` 경로다.
	 * `chunk(size, txManager)` 한 방에 넘기는 옛 API 는 deprecated 됐다 — 그쪽은 chunk 처리를
	 * `TaskletStep` 위에 얹은 구현이었고, 지금은 chunk 전용 step 구현이 따로 있다.
	 *
	 * `allowStartIfComplete(true)` 를 주지 않은 것은 의도다 — 실행마다 새 JobInstance 라
	 * ([IndexJobs.PARAM_REQUESTED_AT]) 이미 완료된 step 을 다시 만나는 일이 없다.
	 */
	@Bean
	fun keywordLoadStep(): Step {
		// 두 얼굴을 **각각** 등록한다 — 하나로 넘기면 오버로드 해소가 한쪽 인터페이스만 잡아
		// 실행마다 카운터를 리셋하는 beforeStep 이 안 불릴 수 있다 ([ChunkProgressLogger] 주석).
		val progress = ChunkProgressLogger("키워드 색인")
		return StepBuilder(IndexJobs.STEP_KEYWORD_LOAD, jobRepository)
			.chunk<PlaceRow, PlaceRow>(chunkSize)
			.transactionManager(transactionManager)
			.reader(keywordPlaceReader())
			.writer(keywordBulkWriter())
			// writer 가 StepExecutionListener 이기도 하다 — 집계를 job 컨텍스트로 올리려고.
			.listener(keywordBulkWriter())
			.listener(progress as ChunkListener<PlaceRow, PlaceRow>)
			.listener(progress as StepExecutionListener)
			.build()
	}

	/**
	 * `since` 가 있으면 델타만, 없으면 전체를 읽는다. `@StepScope` 라서 step 이 시작할 때 비로소
	 * 만들어지고, 그 시점의 `jobExecutionContext` 를 읽을 수 있다 (**늦은 바인딩**).
	 *
	 * 커서가 진짜로 커서로 돌게 하려면 `fetchSize` 와 `connectionAutoCommit(false)` 가 **둘 다**
	 * 필요하다 — 하나만 빠지면 postgres 드라이버가 64k 행을 전부 메모리에 올린다 ([PlaceSql] 주석).
	 */
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
			// 자르지 않고 **생값 그대로** 넘긴다 — 양쪽 어디를 잘라도 같은 밀리초 안의 뒷행이
			// 누락된다 ([PlaceSql.SELECT_SINCE] 주석).
			val watermark = OffsetDateTime.parse(since)
			builder.sql(PlaceSql.SELECT_SINCE)
				.preparedStatementSetter { ps -> ps.setObject(1, watermark) }
				.build()
		}
	}

	/** 쓸 대상도 늦게 바인딩된다 — 전체는 새 버전 인덱스, 증분은 alias 가 들어온다. */
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

	// ---- 전체 재색인: 준비 / 승격 ----

	@Bean
	fun keywordRebuildPrepareStep(): Step =
		StepBuilder("${IndexJobs.KEYWORD_REBUILD}.${IndexJobs.STEP_PREPARE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val ctx = chunkContext.stepContext.stepExecution.jobExecution.executionContext

				// 새 버전을 만들기 **전에** 크래시가 남긴 고아를 치운다. 지금은 alias 가 마지막 정상본을
				// 가리키고 있으므로 '그보다 높은 번호'는 확정된 고아다. 새 버전을 만든 뒤에 부르면
				// 방금 만든 걸 지운다 — 순서가 곧 안전 조건이다 (IndexAdminService.sweepOrphansAbove).
				val swept = admin.sweepOrphansAbove(searchAlias) + admin.sweepOrphansAbove(suggestAlias)
				if (swept.isNotEmpty()) log.warn("이전 실행이 남긴 고아 인덱스 {}개 정리 {}", swept.size, swept.sorted())

				val newSearch = admin.createNextVersion(searchAlias, "es/place_search.json")
				val newSuggest = admin.createNextVersion(suggestAlias, "es/place_suggest.json")
				ctx.putString(IndexJobs.Ctx.SEARCH_INDEX, newSearch)
				ctx.putString(IndexJobs.Ctx.SUGGEST_INDEX, newSuggest)
				// `since` 를 넣지 않는다 → 리더가 전체를 읽는다.
				log.info("키워드 전체 재색인 준비 완료 → {} + {}", newSearch, newSuggest)
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	/**
	 * 적재가 끝난 뒤에만 하는 일들. **순서가 의미를 갖는다.**
	 *
	 * refresh → 스왑 → 정리 → 도장 → 체크포인트. 버전 도장을 **스왑 성공 뒤에** 찍는 게 중요하다
	 * (ADR 0011) — 스왑 전에 찍으면 적재가 실패했을 때 "새 스키마로 색인됐다"는 거짓말이 남는다.
	 * step 을 분리한 덕에 이 순서가 코드 구조로도 드러난다: 적재 step 이 실패하면 이 step 은
	 * 아예 실행되지 않는다.
	 */
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

				// 여기부터는 두 인덱스가 **서빙 중**이다. 아래에서 무엇이 실패하든
				// [OrphanIndexCleanupListener] 가 이걸 지우면 안 된다 (지우면 alias 도 함께 사라진다).
				ctx.putString(IndexJobs.Ctx.PROMOTED, "$newSearch,$newSuggest")

				val removed = admin.reconcile(searchAlias, keepVersions) + admin.reconcile(suggestAlias, keepVersions)
				ctx.putString(IndexJobs.Ctx.REMOVED, removed.sorted().joinToString(","))

				val stamp = IndexMeta.stamp()
				meta.write(IndexMeta.PIPELINE_SEARCH, stamp)
				meta.write(IndexMeta.PIPELINE_SUGGEST, stamp)

				// 전체 재색인 시점을 체크포인트로 심어, 이후 증분이 여기서부터 이어지게 한다.
				LoadProgress.ofJob(step).maxUpdatedAt?.let {
					checkpoints.set(CheckpointStore.PLACE_PIPELINE, it)
					ctx.putString(IndexJobs.Ctx.CHECKPOINT, it.toString())
				}

				log.info("키워드 전체 재색인 승격 완료 — alias 스왑, {}개 정리 {}", removed.size, removed.sorted())
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	// ---- 증분: 준비 / 승격 ----

	/**
	 * 증분은 **살아있는 인덱스에 덮어쓴다.** 그래서 문서 스키마가 바뀐 채로 돌리면 한 인덱스 안에
	 * 옛 스키마 문서와 새 스키마 문서가 섞이고, 그 상태는 오류를 내지 않는다. 섞인 걸 나중에
	 * 감지하기보다 **애초에 못 섞이게** 막는다 (ADR 0011).
	 */
	@Bean
	fun keywordIncrementalPrepareStep(): Step =
		StepBuilder("${IndexJobs.KEYWORD_INCREMENTAL}.${IndexJobs.STEP_PREPARE}", jobRepository)
			.tasklet({ _, chunkContext ->
				val ctx = chunkContext.stepContext.stepExecution.jobExecution.executionContext

				// alias 가 실제로 인덱스를 가리키는지 먼저 본다 — 아니면 전체 재색인이 먼저다.
				admin.indicesBehind(searchAlias).firstOrNull()
					?: error("alias 미설정: $searchAlias — 먼저 전체 재색인이 필요합니다")
				admin.indicesBehind(suggestAlias).firstOrNull()
					?: error("alias 미설정: $suggestAlias")

				val stamp = IndexMeta.stamp()
				meta.requireCompatible(IndexMeta.PIPELINE_SEARCH, stamp, remedy = INCREMENTAL_REMEDY)
				meta.requireCompatible(IndexMeta.PIPELINE_SUGGEST, stamp, remedy = INCREMENTAL_REMEDY)

				// 증분은 alias 로 직접 쓴다 — 같은 place_id 면 덮어쓰기/삭제라 재실행에 안전(멱등).
				ctx.putString(IndexJobs.Ctx.SEARCH_INDEX, searchAlias)
				ctx.putString(IndexJobs.Ctx.SUGGEST_INDEX, suggestAlias)

				// watermark: 저장된 체크포인트. 없으면(첫 실행) 인덱스 max 로 폴백, 그것도 없으면 전체.
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

				// 처리한 델타의 최신 시각까지 watermark 전진 (삭제 행도 여기에 포함되어 전진됨).
				val since = ctx.getString(IndexJobs.Ctx.SINCE, "").ifEmpty { null }?.let(OffsetDateTime::parse)
				val advanced = LoadProgress.ofJob(step).maxUpdatedAt
					?.takeIf { since == null || it.isAfter(since) }
					?.also { checkpoints.set(CheckpointStore.PLACE_PIPELINE, it) }

				ctx.putString(IndexJobs.Ctx.CHECKPOINT, (advanced ?: since)?.toString() ?: "")
				log.info("키워드 증분 완료 — watermark {} → {}", since, advanced ?: since)
				RepeatStatus.FINISHED
			}, transactionManager)
			.build()

	private companion object {
		val log = LoggerFactory.getLogger(KeywordIndexJobConfig::class.java)

		const val INCREMENTAL_REMEDY =
			"POST /admin/reindex 로 전체 재색인하세요. 증분으로는 섞인 인덱스가 됩니다."
	}
}
