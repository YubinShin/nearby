package dev.yubin.search.query

import dev.yubin.search.core.place.SearchDoc
import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.DistanceUnit
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchResponse as EsSearchResponse
import co.elastic.clients.elasticsearch.core.search.Highlight
import co.elastic.clients.elasticsearch.core.search.HighlightField
import co.elastic.clients.util.NamedValue
import dev.yubin.search.observability.QueryMetrics
import kotlinx.coroutines.future.await
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.math.roundToLong

@Service
class PlaceSearchService(
	private val es: ElasticsearchAsyncClient,
	private val metrics: QueryMetrics,
	private val queryLog: QueryLog,
	@Value("\${psp.index.search-alias}") private val alias: String,
) {
	suspend fun search(req: SearchRequest): SearchResponse = metrics.record(CHANNEL) {
		if (req.q.isBlank()) {
			return@record SearchResponse(
				query = req.q,
				total = 0,
				page = req.page,
				size = req.size,
				tookMs = 0,
			)
		}

		val strict = execute(req, PlaceQueries.search(req, relaxed = false), relaxed = false)
		val result =
			if (strict.total > 0) strict
			else execute(req, PlaceQueries.search(req, relaxed = true), relaxed = true)

		queryLog.search(result.query, result.total, result.relaxed, result.tookMs)
		result
	}

	private suspend fun execute(req: SearchRequest, query: Query, relaxed: Boolean): SearchResponse {
		val resp: EsSearchResponse<SearchDoc> = es.search({ s ->
			s.index(alias)
				.query(query)
				.from(req.from)
				.size(req.size)
				.trackTotalHits { t -> t.enabled(true) }
				.highlight(HIGHLIGHT)
			if (req.sort == SortBy.DISTANCE) {
				s.trackScores(true)
				s.sort { so ->
					so.geoDistance { g ->
						g.field("location")
							.location(PlaceQueries.geoPoint(req.lat!!, req.lon!!))
							.order(SortOrder.Asc)
							.unit(DistanceUnit.Meters)
					}
				}
			} else {
				s.sort { so -> so.score { sc -> sc.order(SortOrder.Desc) } }
			}
			s.sort { so -> so.field { f -> f.field(PlaceQueries.TIE_BREAK).order(SortOrder.Asc) } }
		}, SearchDoc::class.java).await()

		return SearchResponse(
			query = req.q,
			total = resp.hits().total()?.value() ?: 0,
			page = req.page,
			size = req.size,
			tookMs = resp.took(),
			relaxed = relaxed,
			hits = resp.hits().hits().mapNotNull { h ->
				h.source()?.let { doc ->
					toHit(
						doc = doc,
						score = h.score() ?: 0.0,
						distanceM = h.sort().firstOrNull()?.doubleValue()?.roundToLong()
							?.takeIf { req.sort == SortBy.DISTANCE },
						highlight = h.highlight().values.flatten(),
					)
				}
			},
		)
	}

	suspend fun byIds(ids: List<String>): Map<String, PlaceHit> {
		if (ids.isEmpty()) return emptyMap()
		return es.mget({ m -> m.index(alias).ids(ids) }, SearchDoc::class.java).await()
			.docs()
			.mapNotNull { item -> item.takeIf { it.isResult }?.result()?.source() }
			.associate { doc -> doc.place_id to toHit(doc, score = 0.0) }
	}

	private fun toHit(
		doc: SearchDoc,
		score: Double,
		distanceM: Long? = null,
		highlight: List<String> = emptyList(),
	) = PlaceHit(
		placeId = doc.place_id,
		name = doc.name,
		branch = doc.branch,
		brand = doc.brand,
		category = doc.category_small ?: doc.category_mid ?: doc.category_large,
		address = doc.road_address ?: doc.jibun_address,
		sigungu = doc.sigungu,
		dong = doc.dong,
		lat = doc.location?.lat,
		lon = doc.location?.lon,
		score = score,
		distanceM = distanceM,
		highlight = highlight,
	)

	companion object {
		const val CHANNEL = "keyword"

		private val HIGHLIGHT: Highlight = Highlight.of { h ->
			h.fields(
				NamedValue.of("name", HighlightField.of { f -> f }),
				NamedValue.of("road_address", HighlightField.of { f -> f }),
				NamedValue.of("jibun_address", HighlightField.of { f -> f }),
			)
		}
	}
}
