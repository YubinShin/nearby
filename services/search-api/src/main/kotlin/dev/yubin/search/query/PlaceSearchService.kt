package dev.yubin.search.query

import dev.yubin.search.core.place.SearchDoc
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.DistanceUnit
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.SearchResponse as EsSearchResponse
import co.elastic.clients.elasticsearch.core.search.Highlight
import co.elastic.clients.elasticsearch.core.search.HighlightField
import co.elastic.clients.util.NamedValue
import dev.yubin.search.observability.QueryMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import kotlin.math.roundToLong

/**
 * 본문 검색 채널 — BM25 + KOMORAN 형태소 분석 (ADR 0008).
 *
 * **alias 로만 질의한다.** 실제 인덱스명(`place_search_v7`)을 코드가 알면 무중단 스왑이 깨진다.
 * 3단계에서 만든 alias 규약을 읽기 경로가 그대로 지킨다.
 *
 * 6단계 하이브리드 결합에서 이 서비스는 "키워드 채널" 하나로 들어가고, 벡터 채널과 RRF 로 합쳐진다
 * (ADR 0003). 그래서 결합에 필요한 것(순위·점수·place_id)을 온전히 돌려주는 게 이 클래스의 계약이다.
 */
@Service
@ConditionalOnProperty(prefix = "psp.role", name = ["query"], havingValue = "true", matchIfMissing = true)
class PlaceSearchService(
	private val es: ElasticsearchClient,
	private val metrics: QueryMetrics,
	private val queryLog: QueryLog,
	@Value("\${psp.index.search-alias}") private val alias: String,
) {

	/**
	 * 검색 한 번.
	 *
	 * 엄격 질의(모든 단어 포함)로 0건이면 조건을 풀어 **한 번 더** 시도한다.
	 * "결과 없음"보다 "덜 정확하지만 뭔가 있음"이 검색 UX 에선 대개 낫고,
	 * 재질의 비용은 *0건일 때만* 발생하니 정상 경로 지연에 영향이 없다.
	 * 다만 사용자가 알 수 있게 응답에 `relaxed=true` 를 표시한다.
	 */
	suspend fun search(req: SearchRequest): SearchResponse = metrics.record(CHANNEL) {
		if (req.q.isBlank()) return@record SearchResponse(req.q, 0, req.page, req.size, 0)

		val strict = execute(req, PlaceQueries.search(req, relaxed = false), relaxed = false)
		val result =
			if (strict.total > 0) strict
			else execute(req, PlaceQueries.search(req, relaxed = true), relaxed = true)

		// 0건·완화 질의는 미등록 어휘의 직접 증거다 (ADR 0008 — 사전의 두 번째 원천).
		queryLog.search(result.query, result.total, result.relaxed, result.tookMs)
		result
	}

	private suspend fun execute(req: SearchRequest, query: Query, relaxed: Boolean): SearchResponse =
		withContext(Dispatchers.IO) {
			val resp: EsSearchResponse<SearchDoc> = es.search({ s ->
				s.index(alias)
					.query(query)
					.from(req.from)
					.size(req.size)
					.trackTotalHits { t -> t.enabled(true) }
					.highlight(HIGHLIGHT)
				if (req.sort == SortBy.DISTANCE) {
					s.sort { so ->
						so.geoDistance { g ->
							g.field("location")
								.location(PlaceQueries.geoPoint(req.lat!!, req.lon!!))
								.order(SortOrder.Asc)
								.unit(DistanceUnit.Meters)
						}
					}
				}
				s
			}, SearchDoc::class.java)

			SearchResponse(
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
							// 거리순일 때만 sort 값에 미터가 실려 온다.
							distanceM = h.sort().firstOrNull()?.doubleValue()?.roundToLong()
								?.takeIf { req.sort == SortBy.DISTANCE },
							highlight = h.highlight().values.flatten(),
						)
					}
				},
			)
		}

	/**
	 * id 로 문서를 직접 집어 온다 (BM25 를 태우지 않는 단순 조회).
	 *
	 * 하이브리드 결합(6단계)에서 **벡터만 찾아낸 문서**를 채우는 데 쓴다. 벡터 payload 에는
	 * 주소가 없어서(용량 절약, `PlaceVectors.payload` 참고) 그대로 내보내면 *어느 엔진이
	 * 찾았느냐에 따라 응답 필드가 들쭉날쭉해진다.* 결합 결과의 모양은 한 가지여야 한다.
	 *
	 * ES `_id` 는 색인 때 `place_id` 로 넣었으므로(`ReindexService.action`) mget 한 번이면 된다.
	 * 검색이 아니라 조회라 `score` 는 없다 — 호출 측이 채워 넣는다.
	 */
	suspend fun byIds(ids: List<String>): Map<String, PlaceHit> {
		if (ids.isEmpty()) return emptyMap()
		return withContext(Dispatchers.IO) {
			es.mget({ m -> m.index(alias).ids(ids) }, SearchDoc::class.java)
				.docs()
				.mapNotNull { item -> item.takeIf { it.isResult }?.result()?.source() }
				.associate { doc -> doc.place_id to toHit(doc, score = 0.0) }
		}
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

		/** 어떤 글자가 걸렸는지 눈으로 확인하는 용도 — 형태소 분석이 제대로 먹었는지 바로 보인다. */
		private val HIGHLIGHT: Highlight = Highlight.of { h ->
			h.fields(
				NamedValue.of("name", HighlightField.of { f -> f }),
				// 브랜드로 걸린 건이 상호명엔 없는 글자로 매칭되므로, 여기가 비면 이유가 안 보인다.
				NamedValue.of("brand", HighlightField.of { f -> f }),
				NamedValue.of("jibun_address", HighlightField.of { f -> f }),
			)
		}
	}
}
