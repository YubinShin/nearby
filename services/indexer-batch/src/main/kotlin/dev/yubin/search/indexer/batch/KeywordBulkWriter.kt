package dev.yubin.search.indexer.batch

import dev.yubin.search.core.place.PlaceDocuments
import dev.yubin.search.core.place.PlaceRow
import dev.yubin.search.indexer.index.BulkAction
import dev.yubin.search.indexer.index.EsBulkIndexer
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

/**
 * chunk 하나를 **검색 인덱스와 자동완성 인덱스 두 곳에** 적용한다.
 *
 * ### 왜 writer 하나가 인덱스 두 개를 쓰나
 * 두 인덱스는 같은 원천 행에서 만들어지고 **같이 갈아치워야** 한다 — 검색은 새 버전이고 자동완성은
 * 옛 버전인 상태가 되면, 자동완성이 제안한 가게를 눌렀는데 검색 결과가 없는 일이 생긴다.
 * step 을 둘로 나누면 원천을 두 번 읽어야 하고(64k 행 × 2), 둘 사이에 원천이 바뀌면 두 인덱스가
 * 서로 다른 시점을 담는다. 한 번 읽어 두 곳에 쓰는 게 싸고 정확하다.
 *
 * ### 왜 upsert 와 delete 가 섞여 있나
 * 소프트 삭제(`deleted_at`)된 행은 ES 에서 **지워야** 한다. 원천의 한 행이 상황에 따라 upsert 이거나
 * delete 인 것이라, 읽기를 나누지 않고 여기서 갈라 보낸다. 둘 다 id 기준이라 멱등하다 (ADR 0001).
 *
 * ### 상태를 필드에 들고 있는데 안전한가
 * 이 빈은 `@StepScope` 다 — **step 실행마다 새 인스턴스**가 만들어지고 끝나면 버려진다.
 * 그리고 색인 job 은 한 번에 하나만 돈다([BatchConfig] 의 단일 스레드 풀). 다만 누적 집계는
 * 필드가 아니라 [LoadProgress] 를 통해 `ExecutionContext` 에 담는다 — 그래야 chunk 커밋과 함께
 * 저장되어 실패한 실행에도 흔적이 남는다.
 *
 * ### 왜 `open` 인가 — 코틀린과 `@StepScope` 가 부딪히는 자리
 * `@StepScope` 빈은 싱글턴 step 이 들고 있을 수 있도록 **스코프 프록시**로 주입된다. 그 프록시는
 * CGLIB 이 이 클래스를 **상속해서** 만드는데, **코틀린 클래스는 기본이 final** 이라 상속이 안 된다.
 * 그래서 컨텍스트가 `AopConfigException` 으로 뜨지 않는다.
 *
 * `kotlin("plugin.spring")` 이 `@Component`·`@Configuration` 붙은 클래스는 알아서 열어주지만,
 * 이 클래스는 어노테이션 없이 `@Bean` 메서드가 직접 만드는 객체라 그 대상이 아니다. 직접 연다.
 */
open class KeywordBulkWriter(
	private val indexer: EsBulkIndexer,
	private val searchTarget: String,
	private val suggestTarget: String,
) : ItemWriter<PlaceRow>, StepExecutionListener {

	private lateinit var progress: LoadProgress

	override fun beforeStep(stepExecution: StepExecution) {
		progress = LoadProgress.of(stepExecution)
	}

	override fun write(chunk: Chunk<out PlaceRow>) {
		val rows = chunk.items
		if (rows.isEmpty()) return

		val searchStats = indexer.bulk(searchTarget, rows.map { action(it, PlaceDocuments::searchDoc) })
		indexer.bulk(suggestTarget, rows.map { action(it, PlaceDocuments::suggestDoc) })

		progress.record(rows, upserted = searchStats.upserted, deleted = searchStats.deleted)
	}

	/** 집계를 job 컨텍스트로 올려 promote step 이 읽게 한다. */
	override fun afterStep(stepExecution: StepExecution): ExitStatus? {
		progress.promoteTo(stepExecution)
		return null   // null = 원래 종료 상태를 그대로 둔다
	}

	/** 소프트 삭제면 Delete, 아니면 Upsert. */
	private fun action(row: PlaceRow, doc: (PlaceRow) -> Map<String, Any?>): BulkAction =
		if (row.deletedAt != null) BulkAction.Delete(row.placeId) else BulkAction.Upsert(row.placeId, doc(row))
}
