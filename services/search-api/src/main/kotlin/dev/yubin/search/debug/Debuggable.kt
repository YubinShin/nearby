package dev.yubin.search.debug

import kotlinx.coroutines.withContext

interface Debuggable<T : Debuggable<T>> {
	val debug: List<CapturedQuery>?

	fun withDebug(queries: List<CapturedQuery>): T
}

suspend fun <T : Debuggable<T>> capturing(debug: Boolean?, block: suspend () -> T): T {
	if (debug != true) return block()

	val capture = DebugCapture()
	return withContext(DebugCapture.contextElement(capture)) { block() }.withDebug(capture.snapshot())
}
