package dev.yubin.search.core.meta

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.elasticsearch._types.Refresh
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [IndexMeta.Stamp] 를 ES 에 읽고 쓴다.
 *
 * **읽는 쪽과 쓰는 쪽이 같은 클래스를 쓰는 것**이 이 파일이 core 에 있는 이유다. 도장을 대조하는
 * 기능인데 정작 도장 형식이 두 앱에서 갈라지면 아무 의미가 없다. 색인기가 늘어나도
 * (`indexer-stream`) 같은 코드를 쓰게 된다.
 *
 * ### 블로킹인 이유 (ADR 0013)
 * 밑에 있는 `ElasticsearchClient` 는 **동기 클라이언트**다. 전에는 그걸 `suspend` +
 * `withContext(Dispatchers.IO)` 로 감싸고 있었는데, 그러면 이 클래스를 쓰는 모든 앱이 코루틴을
 * 짊어진다. 게다가 실제 호출부는 둘 다 기다려도 되는 자리다 —
 * 질의기는 **기동 시 한 번**(`IndexContractGuard`), 색인기는 **job 스레드**. 감싸서 얻는 게 없었다.
 */
@Component
class IndexMetaStore(private val es: ElasticsearchClient) {

	/**
	 * 파이프라인의 도장을 남긴다. **alias 스왑이 성공한 뒤에** 부를 것 —
	 * 실패한 색인의 도장이 남으면 대조가 거짓말을 한다.
	 */
	fun write(pipeline: String, stamp: IndexMeta.Stamp) {
		es.index { it.index(IndexMeta.ES_INDEX).id(pipeline).document(stamp).refresh(Refresh.True) }
	}

	/**
	 * 파이프라인의 도장. 없으면 null (분리 이전 인덱스이거나 아직 재색인 전).
	 *
	 * **"도장이 없다"와 "ES 를 못 읽었다"를 구별한다.** 둘을 뭉뚱그려 null 로 만들면, ES 가 잠깐
	 * 안 뜬 순간에 기동한 질의기가 "도장 없음 → 경고만" 으로 통과해버린다. 그 뒤 ES 가 돌아오면
	 * 어긋난 색인 위에서 서비스하게 된다 — 이 클래스가 막으려던 바로 그 상태다.
	 * 그래서 인덱스가 없는 경우(404)만 null 로 보고, 나머지 오류는 그대로 올린다.
	 */
	fun read(pipeline: String): IndexMeta.Stamp? =
		try {
			es.get({ g -> g.index(IndexMeta.ES_INDEX).id(pipeline) }, IndexMeta.Stamp::class.java).source()
		} catch (e: ElasticsearchException) {
			// 인덱스가 아직 없다 = 아직 아무도 색인하지 않았다. (문서만 없으면 예외 없이 source()=null)
			if (e.status() == HTTP_NOT_FOUND) null else throw e
		}

	/**
	 * 저장된 도장이 [expected] 와 맞지 않으면 **예외를 던진다.**
	 *
	 * 색인기(증분 시작 전)와 질의기(기동 시)가 **같은 함수**를 쓴다. 대조 규칙이 두 벌이면
	 * 한쪽만 느슨해지는데, 그건 이 기능이 막으려는 사고와 정확히 같은 종류다.
	 *
	 * 도장이 아예 없으면 통과시키고 경고만 남긴다 — 분리 이전에 만든 인덱스로도 뜰 수 있어야
	 * 하기 때문이다. 도장이 **있는데 다른** 경우만 막는다.
	 *
	 * @param remedy 사람이 다음에 뭘 해야 하는지. 예외 메시지의 마지막 줄이 된다.
	 */
	fun requireCompatible(pipeline: String, expected: IndexMeta.Stamp, remedy: String) {
		when (val verdict = IndexMeta.verify(read(pipeline), expected)) {
			IndexMeta.Verdict.Ok -> Unit

			IndexMeta.Verdict.Missing ->
				log.warn(
					"[{}] 버전 도장이 없습니다 — 모듈 분리(ADR 0011) 이전에 만든 색인으로 보입니다. " +
						"전체 재색인을 한 번 돌리면 도장이 심어지고 이 경고가 사라집니다.",
					pipeline,
				)

			is IndexMeta.Verdict.Mismatch ->
				throw IllegalStateException(
					buildString {
						append("[$pipeline] 색인된 데이터와 이 프로세스의 계약이 다릅니다.\n")
						verdict.differences.forEach { append("  - ").append(it).append('\n') }
						append("  이 상태로는 오류 없이 결과만 조용히 틀려집니다.\n")
						append("  → ").append(remedy)
					},
				)
		}
	}

	private companion object {
		const val HTTP_NOT_FOUND = 404

		private val log = LoggerFactory.getLogger(IndexMetaStore::class.java)
	}
}
