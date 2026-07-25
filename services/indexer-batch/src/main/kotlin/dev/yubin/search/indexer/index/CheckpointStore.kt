package dev.yubin.search.indexer.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.Refresh
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** ES 에 저장하는 색인 체크포인트 문서. */
data class Checkpoint(val last_updated_at: String? = null)

/**
 * 증분 색인의 watermark 를 **인덱스 내용과 무관하게** 별도로 저장한다.
 *
 * 왜 인덱스의 max(updated_at) 를 안 쓰나: 문서를 '삭제'하면 그 문서는 인덱스에서 사라져 max 를
 * 올리지 못한다 → watermark 가 삭제 지점을 못 넘어가 삭제 행이 매번 재처리된다. 체크포인트를
 * 따로 저장하면 삭제도 정상적으로 watermark 를 전진시킨다. (아키텍처 크리틱 #1·#2 동시 해결)
 *
 * ### Spring Batch 를 들였는데 이건 왜 남아 있나 (ADR 0013)
 * 얼핏 Batch 의 `ExecutionContext` 와 겹쳐 보이지만 **다른 것을 기억한다.**
 * - `ExecutionContext`: "이번 job 이 몇 번째 행까지 읽었나" — **job 안에서** 재시작할 때 쓰고,
 *   job 이 끝나면 쓸모가 없다. 프레임워크 소유.
 * - 여기 watermark: "원천의 어느 시점까지 색인에 반영됐나" — **job 과 job 사이**의 도메인 상태다.
 *   다음 증분 실행이 어디서 이어받을지 정하고, `IndexLagMetrics` 가 lag 을 재는 기준점이기도 하다.
 *
 * 그래서 손으로 만든 restart/retry 는 프레임워크에 넘겼지만(그게 리팩터의 요지다) 이 값은
 * 넘길 대상이 아니었다. 애초에 프레임워크가 대신 갖고 있을 수 있는 종류의 값이 아니다.
 */
@Component
class CheckpointStore(
	private val es: ElasticsearchClient,
	@Value("\${psp.index.checkpoint-index}") private val index: String,
) {

	fun get(pipeline: String): OffsetDateTime? =
		try {
			val resp = es.get({ g -> g.index(index).id(pipeline) }, Checkpoint::class.java)
			resp.source()?.last_updated_at?.let(OffsetDateTime::parse)
		} catch (_: Exception) {
			null // 인덱스/문서가 아직 없음 → 체크포인트 없음
		}

	fun set(pipeline: String, at: OffsetDateTime) {
		es.index { i ->
			i.index(index).id(pipeline).document(Checkpoint(at.toString())).refresh(Refresh.True)
		}
	}

	companion object {
		/** 장소 색인 파이프라인의 체크포인트 문서 id. */
		const val PLACE_PIPELINE = "place"

		/**
		 * 벡터 색인 파이프라인의 체크포인트 문서 id.
		 * 키워드와 **따로** 두는 이유: 임베딩 추론이 느려 두 파이프라인의 진도가 다르다.
		 * 하나로 묶으면 빠른 쪽이 watermark 를 먼저 밀어 느린 쪽이 델타를 건너뛴다.
		 */
		const val PLACE_VECTOR = "place_vector"
	}
}
