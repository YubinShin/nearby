package dev.yubin.search.vector

import dev.yubin.search.query.SearchRequest
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 벡터 채널의 **질의 쪽** 규칙을 순수 함수로 모아둔다 — Qdrant 도 코루틴도 스프링도 모른다.
 * 키워드 채널의 [dev.yubin.search.query.PlaceQueries] 와 같은 역할이고, 같은 이유로 분리했다:
 * 랭킹·필터 규칙은 엔진을 띄우지 않고 단위 테스트로 못박을 수 있어야 한다 (ADR 0009).
 *
 * 색인 쪽 짝인 payload 생성은 [dev.yubin.search.core.vector.PlaceVectorPayload] 로 갔다 —
 * 그건 색인기와 질의기가 **같아야 하는** 문서 스키마라 core 가 소유한다 (ADR 0011).
 * 여기 남은 것은 질의기만 쓰는 규칙이라 core 가 `SearchRequest` 를 알 필요가 없어졌다.
 */
object PlaceVectors {

	/**
	 * 검색 요청 → Qdrant 필터. 조건이 하나도 없으면 null (필터 없는 순수 ANN).
	 *
	 * 필터를 **엔진에 넘기는 것**이 핵심이다. 뜻으로 가까운 상위 N 을 먼저 뽑고 앱에서 거르면,
	 * "강남구" 조건이 붙는 순간 상위 N 이 대부분 탈락해 결과가 텅 빈다.
	 */
	fun filter(req: SearchRequest): Map<String, Any?>? {
		val must = buildList {
			req.sigungu?.let { add(match("sigungu", it)) }
			req.dong?.let { add(match("dong", it)) }
			req.categoryLarge?.let { add(match("category_large", it)) }
			if (req.hasGeo && req.radiusM != null) {
				add(
					mapOf(
						"key" to "location",
						"geo_radius" to mapOf(
							"center" to mapOf("lat" to req.lat, "lon" to req.lon),
							"radius" to req.radiusM.toDouble(),
						),
					),
				)
			}
		}
		return if (must.isEmpty()) null else mapOf("must" to must)
	}

	private fun match(key: String, value: String) = mapOf("key" to key, "match" to mapOf("value" to value))

	/**
	 * 두 좌표 사이 거리(미터). 벡터 채널은 ES 처럼 엔진이 거리를 계산해주지 않아서 앱이 직접 잰다.
	 * 지구를 구로 보는 근사(하버사인)라 수 미터 오차가 있지만, "몇 백 미터"를 보여주는 데는 충분하다.
	 */
	fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Long {
		val dLat = Math.toRadians(lat2 - lat1)
		val dLon = Math.toRadians(lon2 - lon1)
		val a = sin(dLat / 2) * sin(dLat / 2) +
			cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
		return (2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))).toLong()
	}

	private const val EARTH_RADIUS_M = 6_371_000.0
}
