package dev.yubin.search.debug

import kotlinx.coroutines.asContextElement
import tools.jackson.databind.JsonNode
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

data class CapturedQuery(
	val target: String,
	val method: String,
	val path: String,
	val body: JsonNode? = null,
)

class DebugCapture {
	private val queries = CopyOnWriteArrayList<CapturedQuery>()

	fun add(query: CapturedQuery) {
		queries.add(query)
	}

	fun snapshot(): List<CapturedQuery> = queries.toList()

	companion object {
		private val current = ThreadLocal<DebugCapture?>()

		fun active(): DebugCapture? = current.get()

		fun contextElement(capture: DebugCapture): CoroutineContext = current.asContextElement(capture)
	}
}
