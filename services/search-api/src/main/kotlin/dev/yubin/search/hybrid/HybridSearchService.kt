package dev.yubin.search.hybrid

import dev.yubin.search.observability.QueryMetrics
import dev.yubin.search.query.PlaceHit
import dev.yubin.search.query.PlaceSearchService
import dev.yubin.search.query.QueryLog
import dev.yubin.search.query.SearchRequest
import dev.yubin.search.query.SearchResponse
import dev.yubin.search.query.SortBy
import dev.yubin.search.upstream.UpstreamFailure
import dev.yubin.search.vector.PlaceVectorSearchService
import dev.yubin.search.vector.PlaceVectors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

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

		val candidateReq = req.copy(
			size = candidates,
			page = 0,
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
		val hits = present(page, kw.hits, vec.hits, req, hydrate = !kw.report.failed)

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

	private suspend fun runChannel(name: String, block: suspend () -> SearchResponse): ChannelRun {
		val startedAt = System.nanoTime()
		return try {
			val response = block()
			ChannelRun(ChannelReport(name, response.hits.size, elapsedMs(startedAt)), response.hits)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			if (UpstreamFailure.of(e) == null) throw e
			val root = generateSequence(e as Throwable) { it.cause }.last()
			log.warn("hybrid channel '{}' failed, degrading — {}: {}", name, root.javaClass.simpleName, root.message)
			log.debug("hybrid channel '{}' failure detail", name, e)
			ChannelRun(ChannelReport(name, 0, elapsedMs(startedAt), failed = true), emptyList())
		}.also {
			metrics.timer(CHANNEL, name).record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
		}
	}

	private suspend fun present(
		page: List<Rrf.Fused>,
		keywordHits: List<PlaceHit>,
		vectorHits: List<PlaceHit>,
		req: SearchRequest,
		hydrate: Boolean,
	): List<HybridHit> {
		if (page.isEmpty()) return emptyList()

		val fromKeyword = keywordHits.associateBy { it.placeId }
		val fromVector = vectorHits.associateBy { it.placeId }

		val needsLookup =
			if (!hydrate) emptyList() else page.map { it.id }.filterNot { fromKeyword.containsKey(it) }
		val hydrated = if (needsLookup.isEmpty()) {
			emptyMap()
		} else {
			metrics.stage(CHANNEL, "hydrate") {
				try {
					keyword.byIds(needsLookup)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					val root = generateSequence(e as Throwable) { it.cause }.last()
					log.warn("hybrid hydrate failed for {} ids — {}: {}", needsLookup.size, root.javaClass.simpleName, root.message)
					log.debug("hybrid hydrate failure detail", e)
					emptyMap()
				}
			}
		}

		return page.mapNotNull { fused ->
			val base = fromKeyword[fused.id] ?: hydrated[fused.id] ?: fromVector[fused.id] ?: return@mapNotNull null
			val place = base.copy(
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
