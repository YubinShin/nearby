package dev.yubin.search.debug

import co.elastic.clients.json.JsonpMapper
import co.elastic.clients.json.JsonpSerializable
import co.elastic.clients.json.JsonpUtils
import co.elastic.clients.transport.ElasticsearchTransport
import co.elastic.clients.transport.Endpoint
import co.elastic.clients.transport.TransportOptions
import java.util.concurrent.CompletableFuture

class EsCapturingTransport(private val delegate: ElasticsearchTransport) : ElasticsearchTransport {
	override fun <RequestT, ResponseT, ErrorT> performRequest(
		request: RequestT,
		endpoint: Endpoint<RequestT, ResponseT, ErrorT>,
		options: TransportOptions?,
	): ResponseT {
		capture(request, endpoint)
		return delegate.performRequest(request, endpoint, options)
	}

	override fun <RequestT, ResponseT, ErrorT> performRequestAsync(
		request: RequestT,
		endpoint: Endpoint<RequestT, ResponseT, ErrorT>,
		options: TransportOptions?,
	): CompletableFuture<ResponseT> {
		capture(request, endpoint)
		return delegate.performRequestAsync(request, endpoint, options)
	}

	override fun jsonpMapper(): JsonpMapper = delegate.jsonpMapper()

	override fun options(): TransportOptions = delegate.options()

	override fun close() = delegate.close()

	private fun <RequestT> capture(request: RequestT, endpoint: Endpoint<RequestT, *, *>) {
		val capture = DebugCapture.active() ?: return

		capture.addJson(
			target = TARGET,
			method = endpoint.method(request),
			path = endpoint.requestUrl(request),
			json = (endpoint.body(request) as? JsonpSerializable)
				?.let { JsonpUtils.toJsonString(it, delegate.jsonpMapper()) },
		)
	}

	private companion object {
		const val TARGET = "elasticsearch"
	}
}
