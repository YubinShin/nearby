package dev.yubin.search.core.vector

import java.util.UUID

data class VectorPoint(val placeId: String, val vector: FloatArray, val payload: Map<String, Any?>) {
	override fun equals(other: Any?) = other is VectorPoint && other.placeId == placeId
	override fun hashCode() = placeId.hashCode()
}

data class VectorMatch(val placeId: String, val score: Float, val payload: Map<String, Any?>)

object QdrantContract {
	fun pointId(placeId: String): String =
		UUID.nameUUIDFromBytes(placeId.toByteArray(Charsets.UTF_8)).toString()

	const val DISTANCE = "Cosine"

	const val HNSW_M = 16

	const val HNSW_EF_CONSTRUCT = 100

	const val HNSW_EF_SEARCH = 128

	val PAYLOAD_INDEXES = mapOf(
		"sigungu" to "keyword",
		"dong" to "keyword",
		"category_large" to "keyword",
		"category_mid" to "keyword",
		"category_small" to "keyword",
		"location" to "geo",
		"updated_at" to "datetime",
	)

	const val UPDATED_AT = "updated_at"
}
