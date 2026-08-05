package dev.yubin.search.ask.answer

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class AnswerRecord(
	val placeId: String,
	val name: String,
	val category: String?,
	val dong: String?,
	val address: String?,
)

@Component
class AnswerContext(private val mapper: ObjectMapper) {
	fun records(search: JsonNode): List<AnswerRecord> =
		mapper.treeToValue(search, HsearchHits::class.java).hits
			.filter { it.placeId.isNotBlank() && it.name.isNotBlank() }
			.map { AnswerRecord(it.placeId, it.name, it.category, it.dong, it.address) }

	fun render(records: List<AnswerRecord>): String {
		if (records.isEmpty()) return EMPTY
		return records.joinToString("\n", prefix = "$HEADER\n") { record ->
			"- [${record.placeId}] ${record.name}" +
				listOfNotNull(record.category, record.dong, record.address).joinToString("") { " | $it" }
		}
	}

	private companion object {
		const val HEADER = "검색결과 (거리 정보 없음):"
		const val EMPTY = "검색결과: (0건)"
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
