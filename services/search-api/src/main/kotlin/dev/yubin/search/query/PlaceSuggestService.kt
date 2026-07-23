package dev.yubin.search.query

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.SortOrder
import dev.yubin.search.observability.QueryMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * 자동완성 채널 — edge_ngram 전용 인덱스 (ADR 0002 — 용도별 인덱스 분리).
 *
 * 본문 검색과 **다른 인덱스**를 보는 게 요점이다. 자동완성은 타이핑 한 글자마다 불려 호출량이
 * 본문 검색의 몇 배고, 대신 문서는 가볍고 필드가 적다. 부하 특성이 다른 둘을 한 인덱스에 두면
 * 캐시와 세그먼트를 서로 밀어낸다.
 */
@Service
@ConditionalOnProperty(prefix = "psp.role", name = ["query"], havingValue = "true", matchIfMissing = true)
class PlaceSuggestService(
	private val es: ElasticsearchClient,
	private val metrics: QueryMetrics,
	@Value("\${psp.index.suggest-alias}") private val alias: String,
) {

	suspend fun suggest(req: SuggestRequest): SuggestResponse = metrics.record(CHANNEL) {
		if (req.q.isBlank()) return@record SuggestResponse(req.q, 0)

		withContext(Dispatchers.IO) {
			val resp = es.search({ s ->
				s.index(alias)
					.query(PlaceQueries.suggest(req))
					.size(req.size)
					// 자동완성은 "몇 건인지"가 아니라 "상위 몇 개"만 필요하다. 전체 집계를 끄면 그만큼 빨라진다.
					.trackTotalHits { t -> t.enabled(false) }
					// 점수 동점이 대량으로 생긴다(실측: "스타" 상위 20건이 점수 3종). 동점의 순서를 ES 내부
					// doc id 에 맡기면 세그먼트 병합·재색인·레플리카에 따라 순서가 달라진다 — 한 글자 칠 때마다
					// 목록이 튀는 원인이다. place_id 로 못 박아 **결정적**으로 만든다.
					.sort({ so -> so.score { sc -> sc.order(SortOrder.Desc) } })
					.sort({ so -> so.field { f -> f.field("place_id").order(SortOrder.Asc) } })
			}, SuggestDoc::class.java)

			SuggestResponse(
				query = req.q,
				tookMs = resp.took(),
				items = resp.hits().hits().mapNotNull { h ->
					h.source()?.let { doc ->
						SuggestItem(
							placeId = doc.place_id,
							name = doc.name,
							category = doc.category_small,
							dong = doc.dong,
							score = h.score() ?: 0.0,
						)
					}
				},
			)
		}
	}

	companion object {
		const val CHANNEL = "suggest"
	}
}
