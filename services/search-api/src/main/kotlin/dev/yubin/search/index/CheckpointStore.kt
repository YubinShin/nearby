package dev.yubin.search.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.Refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 */
@Component
class CheckpointStore(
	private val es: ElasticsearchClient,
	@Value("\${psp.index.checkpoint-index}") private val index: String,
) {

	suspend fun get(pipeline: String): OffsetDateTime? = withContext(Dispatchers.IO) {
		try {
			val resp = es.get({ g -> g.index(index).id(pipeline) }, Checkpoint::class.java)
			resp.source()?.last_updated_at?.let(OffsetDateTime::parse)
		} catch (_: Exception) {
			null // 인덱스/문서가 아직 없음 → 체크포인트 없음
		}
	}

	suspend fun set(pipeline: String, at: OffsetDateTime): Unit = withContext(Dispatchers.IO) {
		es.index { i ->
			i.index(index).id(pipeline).document(Checkpoint(at.toString())).refresh(Refresh.True)
		}
	}
}
