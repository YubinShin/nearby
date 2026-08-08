package dev.yubin.search.core.meta

import dev.yubin.search.core.analysis.Digest
import dev.yubin.search.core.embed.PlaceVectorText
import dev.yubin.search.core.place.PlaceDocuments
import dev.yubin.search.core.place.PlaceRow
import dev.yubin.search.core.vector.PlaceVectorPayload
import java.time.OffsetDateTime

object DocumentFingerprint {
	private val PROBE_TIME: OffsetDateTime = OffsetDateTime.parse("2026-01-01T00:00:00Z")

	val PROBE: List<PlaceRow> = listOf(
		PlaceRow(
			placeId = "probe-brand-from-name",
			name = "씨유역삼점",
			branch = null,
			brand = null,
			categoryLarge = "소매",
			categoryMid = "종합소매점",
			categorySmall = "편의점",
			sido = "서울특별시",
			sigungu = "강남구",
			dong = "역삼동",
			jibunAddress = "역삼동 123-4",
			roadAddress = "테헤란로 1",
			lon = 127.0361,
			lat = 37.5006,
			updatedAt = PROBE_TIME,
			deletedAt = null,
		),
		PlaceRow(
			placeId = "probe-brand-from-source",
			name = "스타벅스",
			branch = "강남대로점",
			brand = "스타벅스",
			categoryLarge = "음식",
			categoryMid = "비알콜 음료점업",
			categorySmall = "커피전문점",
			sido = "서울특별시",
			sigungu = "강남구",
			dong = "논현동",
			jibunAddress = "논현동 5-6",
			roadAddress = "강남대로 2",
			lon = 127.0245,
			lat = 37.5111,
			updatedAt = PROBE_TIME,
			deletedAt = null,
		),
		PlaceRow(
			placeId = "probe-no-optional-field",
			name = "혼밥대왕",
			branch = null,
			brand = null,
			categoryLarge = null,
			categoryMid = null,
			categorySmall = null,
			sido = null,
			sigungu = null,
			dong = null,
			jibunAddress = null,
			roadAddress = null,
			lon = null,
			lat = null,
			updatedAt = PROBE_TIME,
			deletedAt = null,
		),
		PlaceRow(
			placeId = "probe-soft-deleted",
			name = "폐업한가게",
			branch = null,
			brand = null,
			categoryLarge = null,
			categoryMid = null,
			categorySmall = null,
			sido = null,
			sigungu = null,
			dong = null,
			jibunAddress = null,
			roadAddress = null,
			lon = null,
			lat = null,
			updatedAt = PROBE_TIME,
			deletedAt = PROBE_TIME,
		),
		PlaceRow(
			placeId = "probe-duplicate",
			name = "중복판정된가게",
			branch = null,
			brand = null,
			categoryLarge = null,
			categoryMid = null,
			categorySmall = null,
			sido = null,
			sigungu = null,
			dong = null,
			jibunAddress = null,
			roadAddress = null,
			lon = null,
			lat = null,
			updatedAt = PROBE_TIME,
			deletedAt = null,
			duplicateOf = "probe-brand-from-name",
		),
	)

	fun search(): String = Digest.of(PROBE.map { render(it, PlaceDocuments.searchDoc(it)) })

	fun suggest(): String = Digest.of(PROBE.map { render(it, PlaceDocuments.suggestDoc(it)) })

	fun vector(): String =
		Digest.of(PROBE.map { "${PlaceVectorText.of(it)}#${render(it, PlaceVectorPayload.of(it))}" })

	internal fun render(row: PlaceRow, doc: Map<String, Any?>): String =
		"indexable=${row.indexable}," + doc.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
}
