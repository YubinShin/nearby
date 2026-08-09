package dev.yubin.search.debug

import kotlinx.coroutines.asContextElement
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
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

	fun addJson(target: String, method: String, path: String, json: String?) {
		queries.add(CapturedQuery(target, method, path, json?.let { MAPPER.readTree(it) }))
	}

	fun snapshot(): List<CapturedQuery> = queries.toList()

	companion object {
		private val current = ThreadLocal<DebugCapture?>()

		fun active(): DebugCapture? = current.get()

		fun contextElement(capture: DebugCapture): CoroutineContext = current.asContextElement(capture)
	}
}

fun recordQuery(target: String, method: String, path: String, body: Any?) {
	val capture = DebugCapture.active() ?: return
	capture.add(CapturedQuery(target, method, path, MAPPER.valueToTree(elide(body))))
}

private val MAPPER = ObjectMapper()

private const val MAX_NUMBERS = 16

private fun elide(value: Any?): Any? = when {
	value is Map<*, *> -> value.mapValues { elide(it.value) }
	value is List<*> && value.size > MAX_NUMBERS && value.all { it is Number } -> "<${value.size} floats>"
	value is List<*> -> value.map { elide(it) }
	else -> value
}
