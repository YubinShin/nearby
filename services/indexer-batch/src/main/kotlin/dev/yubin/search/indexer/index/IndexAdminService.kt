package dev.yubin.search.indexer.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 인덱스 생명주기(생성·alias 스왑·삭제)를 앱이 직접 관장한다 (ADR 0002).
 * 무중단 교체의 핵심: **새 버전을 뒤에서 통째로 만든 뒤 alias 만 원자적으로 옮긴다.**
 */
@Service
class IndexAdminService(private val es: ElasticsearchClient) {

	/** `{alias}_v{n}` 중 다음 버전 인덱스를 매핑과 함께 새로 만든다. 만든 인덱스명 반환. */
	suspend fun createNextVersion(alias: String, mappingResource: String): String = withContext(Dispatchers.IO) {
		val name = "${alias}_v${nextVersion(alias)}"
		ClassPathResource(mappingResource).inputStream.use { json ->
			es.indices().create { it.index(name).withJson(json) }
		}
		name
	}

	private fun nextVersion(alias: String): Int {
		val existing = es.indices()
			.get { it.index("${alias}_v*").ignoreUnavailable(true) }
			.indices().keys
		val max = existing.mapNotNull { VERSION_SUFFIX.find(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0
		return max + 1
	}

	/** alias 가 지금 가리키는 인덱스들. (없으면 빈 집합) */
	fun indicesBehind(alias: String): Set<String> =
		try {
			es.indices().getAlias { it.name(alias) }.aliases().keys.toSet()
		} catch (_: Exception) {
			emptySet()
		}

	/**
	 * alias 를 newIndex 로 **원자적으로** 바꿔치기 — 기존 연결 제거 + 새 연결 추가를 한 요청으로.
	 * ES 는 이 _aliases 액션들을 한 덩어리로 적용하므로, 그 사이에 "가리키는 인덱스가 없는" 순간이 없다.
	 * 스왑으로 밀려난(더 이상 안 쓰는) 옛 인덱스들을 반환한다.
	 */
	suspend fun swapAlias(alias: String, newIndex: String): Set<String> = withContext(Dispatchers.IO) {
		val previous = indicesBehind(alias)
		es.indices().updateAliases { u ->
			u.actions { a -> a.remove { r -> r.index("*").alias(alias).mustExist(false) } }
				.actions { a -> a.add { ad -> ad.index(newIndex).alias(alias) } }
		}
		previous - newIndex
	}

	suspend fun deleteIndices(indices: Set<String>) = withContext(Dispatchers.IO) {
		if (indices.isNotEmpty()) es.indices().delete { it.index(indices.toList()) }
	}

	/**
	 * 인덱스에 들어있는 가장 최신 `updated_at` (증분 색인의 watermark).
	 * 인덱스 자체를 진실의 원천으로 삼아 별도 체크포인트 저장소가 필요 없다. 비어있으면 null.
	 */
	suspend fun maxUpdatedAt(index: String, field: String = "updated_at"): OffsetDateTime? =
		withContext(Dispatchers.IO) {
			val resp = es.search(
				{ s -> s.index(index).size(0).aggregations("max_ts") { a -> a.max { m -> m.field(field) } } },
				Void::class.java,
			)
			val millis = resp.aggregations()["max_ts"]?.max()?.value()
			if (millis == null || millis.isNaN() || millis <= 0.0) null
			else Instant.ofEpochMilli(millis.toLong()).atOffset(ZoneOffset.UTC)
		}

	companion object {
		private val VERSION_SUFFIX = Regex("_v(\\d+)$")
	}
}
