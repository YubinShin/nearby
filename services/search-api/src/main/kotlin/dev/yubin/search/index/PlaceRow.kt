package dev.yubin.search.index

import java.time.OffsetDateTime

/** PostGIS `public.place` 한 행. 원천 창고에서 읽은 원본 모양 그대로. */
data class PlaceRow(
	val placeId: String,
	val name: String,
	val branch: String?,
	/**
	 * 복원한 브랜드명. **원천이 준 값이 아니라 우리가 추론한 값**이다 (`public.place_brand`).
	 * `branch`(지점명, '강남역점')와 한 글자 차이라 헷갈리기 쉽다 — 이쪽은 '스타벅스'다.
	 */
	val brand: String?,
	val categoryLarge: String?,
	val categoryMid: String?,
	val categorySmall: String?,
	val sido: String?,
	val sigungu: String?,
	val dong: String?,
	val jibunAddress: String?,
	val roadAddress: String?,
	val lon: Double?,
	val lat: Double?,
	val updatedAt: OffsetDateTime,
	val deletedAt: OffsetDateTime?,   // 값이 있으면 소프트 삭제 → 색인 시 ES 문서를 지운다
)
