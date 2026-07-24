package dev.yubin.search.hybrid

import dev.yubin.search.observability.QueryMetrics
import dev.yubin.search.query.PlaceHit
import dev.yubin.search.query.PlaceSearchService
import dev.yubin.search.query.QueryLog
import dev.yubin.search.query.SearchRequest
import dev.yubin.search.query.SearchResponse
import dev.yubin.search.query.SortBy
import dev.yubin.search.vector.PlaceVectorSearchService
import dev.yubin.search.vector.PlaceVectors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * 하이브리드 채널 — 키워드(글자)와 벡터(뜻)를 **앱에서** 합친다 (ADR 0003).
 *
 * 두 채널은 서로의 실패를 메운다. 5단계 실측이 그걸 그대로 보여줬다.
 *  - 키워드는 `회 먹을 데` 를 못 찾는다 — 글자가 하나도 안 겹친다.
 *  - 벡터는 `스타벅스` 에 대해 **"없다"고 말할 줄 모른다** — 아무거나 제일 가까운 걸 준다 (크리틱 #17).
 *
 * 그래서 이 클래스가 하는 일은 세 가지다.
 *  1. **동시에 부른다** — 순차로 부르면 두 지연의 합이지만, 팬아웃하면 느린 쪽 하나에 수렴한다.
 *  2. **순위로 합친다** — 점수 스케일이 다르므로 [Rrf] 에 등수만 넘긴다.
 *  3. **한쪽이 죽어도 답한다** — 채널이 둘이면 고장날 곳도 둘이다. 하이브리드가 각 채널보다
 *     *덜* 안정적이면 합칠 이유가 없다. 실패는 응답의 `degraded`/`channels` 로 드러낸다.
 */
@Service
@ConditionalOnProperty(
	name = ["psp.vector.enabled", "psp.hybrid.enabled"],
	havingValue = "true",
	matchIfMissing = true,
)
class HybridSearchService(
	private val keyword: PlaceSearchService,
	private val vector: PlaceVectorSearchService,
	private val metrics: QueryMetrics,
	private val queryLog: QueryLog,
	@Value("\${psp.hybrid.k}") private val k: Int,
	@Value("\${psp.hybrid.candidates}") private val candidates: Int,
	@Value("\${psp.hybrid.keyword-weight}") private val keywordWeight: Double,
	@Value("\${psp.hybrid.vector-weight}") private val vectorWeight: Double,
) {

	suspend fun search(req: SearchRequest): HybridResponse = metrics.record(CHANNEL) {
		if (req.q.isBlank()) {
			return@record HybridResponse(req.q, 0, req.page, req.size, 0)
		}
		val startedAt = System.nanoTime()

		/*
		 * 각 채널에 **최종 페이지보다 깊게** 요구한다. 상위 10개씩만 받아 합치면, 한 채널이
		 * 11등에 둔 정답은 다른 채널이 1등을 줘도 결합 자체에 못 들어온다. 결합의 이득은
		 * "한쪽이 놓친 걸 다른 쪽이 건진다"인데, 얕게 뜨면 그 이득이 통째로 사라진다.
		 */
		val candidateReq = req.copy(
			size = candidates,
			page = 0,
			// 거리순으로 뽑으면 두 채널 모두 '가까운 순'이 되어 등수에 관련도가 안 남는다.
			// 거리 다듬기는 결합 뒤에 할 일이다 (7단계).
			sort = SortBy.RELEVANCE,
		)

		val (kw, vec) = coroutineScope {
			val keywordRun = async { runChannel(KEYWORD) { keyword.search(candidateReq) } }
			val vectorRun = async { runChannel(VECTOR) { vector.search(candidateReq) } }
			keywordRun.await() to vectorRun.await()
		}

		val fused = metrics.stage(CHANNEL, "fuse") {
			Rrf.fuse(
				listOf(
					Rrf.Channel(KEYWORD, kw.hits.map { it.placeId }, keywordWeight),
					Rrf.Channel(VECTOR, vec.hits.map { it.placeId }, vectorWeight),
				),
				k = k,
			)
		}

		val page = fused.drop(req.from).take(req.size)
		val hits = present(page, kw.hits, vec.hits, req)

		val tookMs = (System.nanoTime() - startedAt) / 1_000_000
		queryLog.search(req.q, fused.size.toLong(), relaxed = false, tookMs = tookMs, channel = CHANNEL)

		HybridResponse(
			query = req.q,
			total = fused.size.toLong(),
			page = req.page,
			size = req.size,
			tookMs = tookMs,
			degraded = kw.report.failed || vec.report.failed,
			channels = listOf(kw.report, vec.report),
			hits = hits,
		)
	}

	/** 채널 하나를 실행하고 **예외를 여기서 삼킨다.** 위 클래스 주석 3번의 구현부. */
	private suspend fun runChannel(name: String, block: suspend () -> SearchResponse): ChannelRun {
		val startedAt = System.nanoTime()
		return try {
			val response = block()
			ChannelRun(ChannelReport(name, response.hits.size, elapsedMs(startedAt)), response.hits)
		} catch (e: Exception) {
			// 실패를 통계로만 남기면 원인을 못 쫓는다. 스택은 로그로, 사실은 응답으로 알린다.
			log.warn("hybrid channel '{}' failed, degrading", name, e)
			ChannelRun(ChannelReport(name, 0, elapsedMs(startedAt), failed = true), emptyList())
		} finally {
			metrics.timer(CHANNEL, name).record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
		}
	}

	/**
	 * 결합된 등수를 사용자에게 보여줄 모양으로 되돌린다.
	 *
	 * 벡터만 찾은 문서는 payload 에 주소가 없다. 그대로 내보내면 **어느 엔진이 찾았느냐에 따라
	 * 응답 필드가 들쭉날쭉해진다** — 결합해서 하나로 준다고 해놓고 출처가 비쳐 보이는 셈이다.
	 * 그래서 부족한 것만 ES 에서 한 번에 집어 온다(mget, 최대 [SearchRequest.MAX_SIZE] 건).
	 * 이 조회가 실패해도 검색은 성공시킨다 — 주소 없는 결과가 결과 없음보다 낫다.
	 */
	private suspend fun present(
		page: List<Rrf.Fused>,
		keywordHits: List<PlaceHit>,
		vectorHits: List<PlaceHit>,
		req: SearchRequest,
	): List<HybridHit> {
		if (page.isEmpty()) return emptyList()

		val fromKeyword = keywordHits.associateBy { it.placeId }
		val fromVector = vectorHits.associateBy { it.placeId }

		val needsLookup = page.map { it.id }.filterNot { fromKeyword.containsKey(it) }
		val hydrated = if (needsLookup.isEmpty()) {
			emptyMap()
		} else {
			metrics.stage(CHANNEL, "hydrate") {
				runCatching { keyword.byIds(needsLookup) }
					.onFailure { log.warn("hybrid hydrate failed for {} ids", needsLookup.size, it) }
					.getOrDefault(emptyMap())
			}
		}

		return page.mapNotNull { fused ->
			val base = fromKeyword[fused.id] ?: hydrated[fused.id] ?: fromVector[fused.id] ?: return@mapNotNull null
			val place = base.copy(
				// 정렬 근거를 점수 자리에 둔다. 원점수는 scores 로 따로 나간다.
				score = fused.score,
				distanceM = distanceM(base, req),
			)
			HybridHit(
				place = place,
				ranks = fused.ranks,
				scores = buildMap {
					fromKeyword[fused.id]?.let { put(KEYWORD, it.score) }
					fromVector[fused.id]?.let { put(VECTOR, it.score) }
				},
			)
		}
	}

	/**
	 * 좌표가 들어왔으면 **정렬과 무관하게** 거리를 채워 준다. 반경 필터로 좁혀 놓고도 응답에
	 * 거리가 없으면 클라이언트가 다시 계산해야 한다 — 서버가 이미 아는 값이다.
	 */
	private fun distanceM(hit: PlaceHit, req: SearchRequest): Long? =
		if (req.hasGeo && hit.lat != null && hit.lon != null) {
			PlaceVectors.distanceM(req.lat!!, req.lon!!, hit.lat!!, hit.lon!!)
		} else {
			null
		}

	private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

	private data class ChannelRun(val report: ChannelReport, val hits: List<PlaceHit>)

	companion object {
		const val CHANNEL = "hybrid"
		const val KEYWORD = PlaceSearchService.CHANNEL
		const val VECTOR = PlaceVectorSearchService.CHANNEL

		private val log = LoggerFactory.getLogger(HybridSearchService::class.java)
	}
}
