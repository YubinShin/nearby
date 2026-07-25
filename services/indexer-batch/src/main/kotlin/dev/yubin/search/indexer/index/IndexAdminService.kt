package dev.yubin.search.indexer.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import dev.yubin.search.core.index.IndexVersion
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 인덱스 생명주기(생성·alias 스왑·삭제)를 앱이 직접 관장한다 (ADR 0002).
 * 무중단 교체의 핵심: **새 버전을 뒤에서 통째로 만든 뒤 alias 만 원자적으로 옮긴다.**
 *
 * 전부 블로킹이다 (ADR 0013). 감싸고 있던 `withContext(Dispatchers.IO)` 를 걷어낸 것뿐이고,
 * 안쪽 `ElasticsearchClient` 는 원래부터 동기였다. 부르는 쪽은 Batch job 의 tasklet 뿐이다.
 */
@Service
class IndexAdminService(private val es: ElasticsearchClient) {

	/** `{alias}_{yyyyMMddHHmmss}` 새 버전 인덱스를 매핑과 함께 만든다. 만든 인덱스명 반환. */
	fun createNextVersion(alias: String, mappingResource: String): String {
		val name = IndexVersion.newName(alias)
		ClassPathResource(mappingResource).inputStream.use { json ->
			es.indices().create { it.index(name).withJson(json) }
		}
		return name
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
	fun swapAlias(alias: String, newIndex: String): Set<String> {
		val previous = indicesBehind(alias)
		es.indices().updateAliases { u ->
			u.actions { a -> a.remove { r -> r.index("*").alias(alias).mustExist(false) } }
				.actions { a -> a.add { ad -> ad.index(newIndex).alias(alias) } }
		}
		return previous - newIndex
	}

	fun deleteIndices(indices: Set<String>) {
		if (indices.isNotEmpty()) es.indices().delete { it.index(indices.toList()) }
	}

	/**
	 * alias 가 가리키는 현재 버전보다 **높은 번호**의 인덱스를 지운다 — 크래시가 남긴 고아다.
	 *
	 * ### 왜 [reconcile] 로는 이걸 못 치우나
	 * [reconcile] 은 "현재보다 높은 번호는 진행 중인 빌드일 수 있다"는 이유로 손대지 않는다.
	 * 그래서 OOM·SIGKILL 로 죽어 남은 반쯤 만든 인덱스는 **영원히 살아남고**, 다음 재색인이
	 * 성공하면 그게 '현재보다 낮은 것 중 가장 최신' 자리에 앉아 **롤백본 한 칸을 차지한다.**
	 * 그 대가로 진짜 마지막 정상본이 대신 지워진다 — 롤백(ADR 0002)이 반쯤 만든 인덱스를 서빙하게 된다.
	 *
	 * ### 언제 부르는 게 안전한가
	 * **새 버전을 만들기 전**, 즉 prepare step 의 맨 앞에서 부른다. 색인 job 은 한 번에 하나만
	 * 돌기 때문에([BatchConfig] 의 단일 스레드 풀) 그 시점에 '현재보다 높은 번호'는 진행 중인
	 * 빌드가 아니라 **확정된 고아**뿐이다. 새 버전을 만든 뒤에 부르면 방금 만든 걸 지운다.
	 */
	fun sweepOrphansAbove(alias: String): Set<String> {
		val current = indicesBehind(alias).mapNotNull { IndexVersion.tokenOf(alias, it) }.maxOrNull()
			?: return emptySet()   // alias 미설정 → 기준 없음. 첫 재색인 전이므로 판단을 보류한다.

		val above = versionsOf(alias).filter { it.second > current }.map { it.first }.toSet()
		deleteIndices(above)
		return above
	}

	/**
	 * 버전 인덱스 정리. alias 가 가리키는 **현재 버전(keep 개, 현재 포함)만 남기고** 그보다 낮은
	 * 번호를 지운다.
	 *
	 * **현재보다 높은 번호는 손대지 않는다** — 진행 중인 새 빌드일 수 있어서다(새 버전은 항상
	 * max+1 로 생긴다). 그래서 크래시가 남긴 고아는 여기서 안 지워진다 — 그건 [sweepOrphansAbove]
	 * 담당이다. alias 가 없으면 기준이 없으니 아무것도 지우지 않는다. 지운 인덱스명을 반환한다.
	 */
	fun reconcile(alias: String, keep: Int): Set<String> {
		val current = indicesBehind(alias).mapNotNull { IndexVersion.tokenOf(alias, it) }.maxOrNull()
			?: return emptySet()

		val below = versionsOf(alias)
			.filter { it.second < current }   // 문자열 비교 = 시간 비교(고정폭 14자리)
			.sortedByDescending { it.second }

		val doomed = below.drop((keep - 1).coerceAtLeast(0)).map { it.first }.toSet()
		deleteIndices(doomed)
		return doomed
	}

	/** `{alias}_{토큰}` 꼴로 존재하는 버전 인덱스들 — `(인덱스명, 토큰)` 쌍. */
	private fun versionsOf(alias: String): List<Pair<String, String>> =
		es.indices()
			.get { it.index("${alias}_*").ignoreUnavailable(true) }
			.indices().keys
			.mapNotNull { name -> IndexVersion.tokenOf(alias, name)?.let { name to it } }

	/**
	 * 인덱스에 들어있는 가장 최신 `updated_at` (증분 색인의 watermark).
	 * 인덱스 자체를 진실의 원천으로 삼아 별도 체크포인트 저장소가 필요 없다. 비어있으면 null.
	 */
	fun maxUpdatedAt(index: String, field: String = "updated_at"): OffsetDateTime? {
		val resp = es.search(
			{ s -> s.index(index).size(0).aggregations("max_ts") { a -> a.max { m -> m.field(field) } } },
			Void::class.java,
		)
		val millis = resp.aggregations()["max_ts"]?.max()?.value()
		return if (millis == null || millis.isNaN() || millis <= 0.0) null
		else Instant.ofEpochMilli(millis.toLong()).atOffset(ZoneOffset.UTC)
	}
}
