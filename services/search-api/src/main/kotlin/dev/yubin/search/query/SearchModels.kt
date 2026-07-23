package dev.yubin.search.query

/** 정렬 기준. 기본은 관련도, 좌표가 있으면 거리순도 고를 수 있다. */
enum class SortBy { RELEVANCE, DISTANCE }

/**
 * 검색 요청을 **정규화한 값 객체**. HTTP 파라미터 파싱/클램핑을 여기 한 곳에 모은다.
 *
 * 컨트롤러가 아니라 이 객체가 규칙(크기 상한·좌표 짝 맞음·반경 기본값)을 갖는 이유는,
 * 순수 함수라 테스트가 쉽고 나중에 다른 진입점(gRPC·배치)에서도 그대로 재사용되기 때문이다.
 */
data class SearchRequest(
	val q: String,
	val size: Int = DEFAULT_SIZE,
	val page: Int = 0,
	val sigungu: String? = null,
	val dong: String? = null,
	val categoryLarge: String? = null,
	val lat: Double? = null,
	val lon: Double? = null,
	val radiusM: Int? = null,
	val sort: SortBy = SortBy.RELEVANCE,
) {
	/** ES from 오프셋. */
	val from: Int get() = page * size

	/** 좌표가 둘 다 있어야 위치 기능(반경 필터·거리 정렬)이 켜진다. */
	val hasGeo: Boolean get() = lat != null && lon != null

	companion object {
		const val DEFAULT_SIZE = 10
		const val MAX_SIZE = 50
		const val MAX_PAGE = 99
		const val DEFAULT_RADIUS_M = 2_000
		const val MAX_RADIUS_M = 50_000

		/**
		 * 바깥 세계(질의 파라미터)를 안전한 범위로 접어 넣는다.
		 * 잘못된 값은 예외 대신 **가장 가까운 합법값으로 클램핑** — 검색은 조금 관대해도 되고,
		 * 페이지 크기 같은 값은 막지 않으면 그대로 클러스터 부하가 된다.
		 */
		fun of(
			q: String?,
			size: Int? = null,
			page: Int? = null,
			sigungu: String? = null,
			dong: String? = null,
			categoryLarge: String? = null,
			lat: Double? = null,
			lon: Double? = null,
			radiusM: Int? = null,
			sort: String? = null,
		): SearchRequest {
			val geo = lat != null && lon != null
			return SearchRequest(
				q = q?.trim().orEmpty(),
				size = (size ?: DEFAULT_SIZE).coerceIn(1, MAX_SIZE),
				page = (page ?: 0).coerceIn(0, MAX_PAGE),
				sigungu = sigungu?.trim()?.ifBlank { null },
				dong = dong?.trim()?.ifBlank { null },
				categoryLarge = categoryLarge?.trim()?.ifBlank { null },
				lat = lat.takeIf { geo },
				lon = lon.takeIf { geo },
				radiusM = if (geo) (radiusM ?: DEFAULT_RADIUS_M).coerceIn(1, MAX_RADIUS_M) else null,
				// 좌표 없이 거리순을 요청하면 정렬 기준이 없다 → 조용히 관련도순으로 되돌린다.
				sort = if (geo && sort.equals("distance", ignoreCase = true)) SortBy.DISTANCE else SortBy.RELEVANCE,
			)
		}
	}
}

/** 자동완성 요청. 본문 검색보다 규칙이 단순하다(필터 없음, 소량). */
data class SuggestRequest(val q: String, val size: Int = DEFAULT_SIZE) {
	companion object {
		const val DEFAULT_SIZE = 8
		const val MAX_SIZE = 20

		fun of(q: String?, size: Int? = null) =
			SuggestRequest(q?.trim().orEmpty(), (size ?: DEFAULT_SIZE).coerceIn(1, MAX_SIZE))
	}
}

// ---- 응답 ----

data class PlaceHit(
	val placeId: String,
	val name: String,
	val branch: String?,
	val category: String?,
	val address: String?,
	val sigungu: String?,
	val dong: String?,
	val lat: Double?,
	val lon: Double?,
	val score: Double,
	/** 거리순 정렬일 때만 채워진다 (미터). */
	val distanceM: Long? = null,
	/** 어느 글자가 왜 걸렸는지 — 형태소 분석 결과를 눈으로 확인하는 용도. */
	val highlight: List<String> = emptyList(),
)

data class SearchResponse(
	val query: String,
	val total: Long,
	val page: Int,
	val size: Int,
	val tookMs: Long,
	/** 엄격 질의(모든 단어 포함)로 0건이라 조건을 풀어 재질의했는지. */
	val relaxed: Boolean = false,
	val hits: List<PlaceHit> = emptyList(),
)

data class SuggestItem(
	val placeId: String,
	val name: String,
	val category: String?,
	val dong: String?,
	val score: Double,
)

data class SuggestResponse(
	val query: String,
	val tookMs: Long,
	val items: List<SuggestItem> = emptyList(),
)

/**
 * 검색창 한 번의 입력에 대한 통합 응답 — 추천어(자동완성)와 미리보기(본문 검색)를 함께 준다.
 * 두 인덱스를 **동시에** 부른 결과라, tookMs 는 둘의 합이 아니라 느린 쪽에 가깝다 (ADR 0006).
 */
data class InstantResponse(
	val query: String,
	val tookMs: Long,
	val suggestions: List<SuggestItem> = emptyList(),
	val preview: List<PlaceHit> = emptyList(),
)
