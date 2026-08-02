package dev.yubin.search.core.place

import java.time.OffsetDateTime

data class PlaceRow(
	val placeId: String,
	val name: String,
	val branch: String?,

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
	val deletedAt: OffsetDateTime?,
	val duplicateOf: String? = null,
) {
	val indexable: Boolean get() = deletedAt == null && duplicateOf == null
}
