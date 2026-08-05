package dev.yubin.search.ask.search

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class PlaceRecord(
	val placeId: String,
	val name: String,
	val category: String?,
	val dong: String?,
	val address: String?,
)

data class HsearchRecords(val records: List<PlaceRecord>, val unrenderable: Int)

object HsearchContract {
	val REQUIRED = listOf("placeId", "name")
	val OPTIONAL = listOf("category", "dong", "address")

	fun decode(body: JsonNode, mapper: ObjectMapper): HsearchRecords {
		val hits = mapper.treeToValue(body, HsearchHits::class.java).hits
		val records = hits
			.filter { it.placeId.isNotBlank() && it.name.isNotBlank() }
			.map { PlaceRecord(it.placeId, it.name, it.category, it.dong, it.address) }
		return HsearchRecords(records, hits.size - records.size)
	}
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class HsearchHits(val hits: List<HsearchHit> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class HsearchHit(
	val placeId: String = "",
	val name: String = "",
	val category: String? = null,
	val dong: String? = null,
	val address: String? = null,
)
