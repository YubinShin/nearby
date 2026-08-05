package dev.yubin.search.ask.answer

import dev.yubin.search.ask.search.PlaceRecord
import org.springframework.stereotype.Component

@Component
class AnswerContext {
	fun render(records: List<PlaceRecord>): String {
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
