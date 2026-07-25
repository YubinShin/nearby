package dev.yubin.search.indexer.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import dev.yubin.search.core.index.IndexVersion
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

	/** `{alias}_{yyyyMMddHHmmss}` 새 버전 인덱스를 매핑과 함께 만든다. 만든 인덱스명 반환. */
	suspend fun createNextVersion(alias: String, mappingResource: String): String = withContext(Dispatchers.IO) {
		val name = IndexVersion.newName(alias)
		ClassPathResource(mappingResource).inputStream.use { json ->
			es.indices().create { it.index(name).withJson(json) }
		}
		name
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
	 * 버전 인덱스 정리. alias 가 가리키는 **현재 버전(keep 개, 현재 포함)만 남기고** 그보다 낮은
	 * 번호를 지운다. 취소·실패로 남은 고아 인덱스도 함께 사라진다.
	 *
	 * **현재보다 높은 번호는 손대지 않는다** — 진행 중인 새 빌드일 수 있어서다(새 버전은 항상
	 * max+1 로 생긴다). alias 가 없으면 기준이 없으니 아무것도 지우지 않는다. 지운 인덱스명을 반환한다.
	 */
	suspend fun reconcile(alias: String, keep: Int): Set<String> = withContext(Dispatchers.IO) {
		val current = indicesBehind(alias).mapNotNull { IndexVersion.tokenOf(alias, it) }.maxOrNull()
			?: return@withContext emptySet()

		val below = es.indices()
			.get { it.index("${alias}_*").ignoreUnavailable(true) }
			.indices().keys
			.mapNotNull { name -> IndexVersion.tokenOf(alias, name)?.let { name to it } }
			.filter { it.second < current }   // 문자열 비교 = 시간 비교(고정폭 14자리)
			.sortedByDescending { it.second }

		val doomed = below.drop((keep - 1).coerceAtLeast(0)).map { it.first }.toSet()
		deleteIndices(doomed)
		doomed
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
}
