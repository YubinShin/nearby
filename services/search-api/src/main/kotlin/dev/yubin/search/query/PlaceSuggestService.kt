package dev.yubin.search.query

import dev.yubin.search.core.place.SuggestDoc
import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.SortOrder
import dev.yubin.search.observability.QueryMetrics
import kotlinx.coroutines.future.await
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class PlaceSuggestService(
	private val es: ElasticsearchAsyncClient,
	private val metrics: QueryMetrics,
	private val queryLog: QueryLog,
	@Value("\${psp.index.suggest-alias}") private val alias: String,
) {
	suspend fun suggest(req: SuggestRequest): SuggestResponse = metrics.record(CHANNEL) {
		if (req.q.isBlank()) return@record SuggestResponse(req.q, 0)

		val resp = es.search({ s ->
			s.index(alias)
				.query(PlaceQueries.suggest(req))
				.size(req.size)

				.trackTotalHits { t -> t.enabled(false) }

				.sort({ so -> so.score { sc -> sc.order(SortOrder.Desc) } })
				.sort({ so -> so.field { f -> f.field(PlaceQueries.TIE_BREAK).order(SortOrder.Asc) } })
		}, SuggestDoc::class.java).await()

		val items = resp.hits().hits().mapNotNull { h ->
			h.source()?.let { doc ->
				SuggestItem(
					placeId = doc.place_id,
					name = doc.name,
					brand = doc.brand,
					category = doc.category_small,
					dong = doc.dong,
					score = h.score() ?: 0.0,
				)
			}
		}
		queryLog.suggest(req.q, items.size, resp.took())

		SuggestResponse(query = req.q, tookMs = resp.took(), items = items)
	}

	companion object {
		const val CHANNEL = "suggest"
	}
}
