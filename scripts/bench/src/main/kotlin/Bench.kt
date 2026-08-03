import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.JsonpMapper
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import jakarta.json.spi.JsonProvider
import jakarta.json.stream.JsonGenerator
import jakarta.json.stream.JsonParser
import java.lang.reflect.Type
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.hc.core5.http.HttpHost
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

data class GeoPointValue(val lat: Double? = null, val lon: Double? = null)

data class SearchDoc(
	val place_id: String = "",
	val name: String = "",
	val branch: String? = null,
	val brand: String? = null,
	val category_large: String? = null,
	val category_mid: String? = null,
	val category_small: String? = null,
	val sigungu: String? = null,
	val dong: String? = null,
	val road_address: String? = null,
	val jibun_address: String? = null,
	val location: GeoPointValue? = null,
)

private val inFlight = AtomicInteger()
private val maxInFlight = AtomicInteger()

val decodeThreadGroups = ConcurrentHashMap<String, Int>()

private fun groupOf(name: String): String = when {
	name.startsWith("DefaultDispatcher-worker") -> "DefaultDispatcher-worker (Dispatchers.IO)"
	name.startsWith("elasticsearch-rest-client") -> "elasticsearch-rest-client (HC5 I/O reactor)"
	else -> name
}

class ThreadRecordingJsonpMapper(private val delegate: JsonpMapper) : JsonpMapper {
	override fun jsonProvider(): JsonProvider = delegate.jsonProvider()

	override fun <T : Any?> deserialize(parser: JsonParser, type: Type): T {
		decodeThreadGroups.merge(groupOf(Thread.currentThread().name), 1, Int::plus)
		return delegate.deserialize(parser, type)
	}

	override fun <T : Any?> deserialize(parser: JsonParser, type: Type, event: JsonParser.Event): T {
		decodeThreadGroups.merge(groupOf(Thread.currentThread().name), 1, Int::plus)
		return delegate.deserialize(parser, type, event)
	}

	override fun <T : Any?> serialize(value: T, generator: JsonGenerator) = delegate.serialize(value, generator)

	override fun ignoreUnknownFields(): Boolean = delegate.ignoreUnknownFields()

	override fun <T : Any?> attribute(name: String): T? = delegate.attribute(name)

	override fun <T : Any?> withAttribute(name: String, value: T): JsonpMapper =
		ThreadRecordingJsonpMapper(delegate.withAttribute(name, value))
}

private fun buildSearchBody(hits: Int): ByteArray {
	val sb = StringBuilder(hits * 400)
	sb.append("""{"took":7,"timed_out":false,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},""")
	sb.append(""""hits":{"total":{"value":$hits,"relation":"eq"},"max_score":12.5,"hits":[""")
	for (i in 0 until hits) {
		if (i > 0) sb.append(',')
		sb.append(
			"""{"_index":"place_search","_id":"p$i","_score":${12.5 - i * 0.01},"_source":{""" +
				""""place_id":"p$i","name":"스타벅스 강남대로${i}점","branch":"강남대로${i}점",""" +
				""""brand":"스타벅스","category_large":"음식","category_mid":"카페","category_small":"커피전문점",""" +
				""""sigungu":"강남구","dong":"역삼동","road_address":"서울 강남구 강남대로 ${100 + i}",""" +
				""""jibun_address":"서울 강남구 역삼동 ${800 + i}-${i % 30}",""" +
				""""location":{"lat":${37.4 + i * 0.0001},"lon":${127.02 + i * 0.0001}}}}""",
		)
	}
	sb.append("]}}")
	return sb.toString().toByteArray(Charsets.UTF_8)
}

private fun startStub(delayMs: Long, hits: Int): HttpServer {
	val body = buildSearchBody(hits)
	val info = (
		"""{"name":"stub","cluster_name":"stub","version":{"number":"9.4.2",""" +
			""""build_flavor":"default","build_type":"docker","build_hash":"x","build_date":"2026-01-01",""" +
			""""build_snapshot":false,"lucene_version":"10.0.0","minimum_wire_compatibility_version":"8.19.0",""" +
			""""minimum_index_compatibility_version":"8.0.0"},"tagline":"You Know, for Search"}"""
		).toByteArray(Charsets.UTF_8)

	val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 1024)
	server.executor = Executors.newCachedThreadPool()
	server.createContext("/") { ex: HttpExchange ->
		val cur = inFlight.incrementAndGet()
		maxInFlight.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
		try {
			ex.requestBody.readBytes()
			val payload = if (ex.requestURI.path.contains("_search")) {
				if (delayMs > 0 && ex.requestURI.path.startsWith("/slow")) Thread.sleep(delayMs)
				body
			} else {
				info
			}
			ex.responseHeaders.add("Content-Type", "application/json")
			ex.responseHeaders.add("X-Elastic-Product", "Elasticsearch")
			ex.sendResponseHeaders(200, payload.size.toLong())
			ex.responseBody.use { it.write(payload) }
		} finally {
			inFlight.decrementAndGet()
		}
	}
	server.start()
	return server
}

private fun threadHistogram(): Map<String, Int> {
	val groups = ConcurrentHashMap<String, Int>()
	for (t in Thread.getAllStackTraces().keys) {
		val n = t.name
		val key = when {
			n.startsWith("DefaultDispatcher-worker") -> "DefaultDispatcher-worker (Dispatchers.IO/Default)"
			n.startsWith("elasticsearch-rest-client") -> "elasticsearch-rest-client (HC5 I/O reactor)"
			n.startsWith("Thread-") || n.startsWith("pool-") -> "stub-server"
			else -> "other:$n"
		}
		groups.merge(key, 1, Int::plus)
	}
	return groups
}

private fun percentile(sorted: LongArray, p: Double): Long {
	if (sorted.isEmpty()) return 0
	val idx = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size) - 1
	return sorted[idx]
}

fun main(args: Array<String>) {
	val mode = args.getOrNull(0) ?: "sync"
	val concurrency = (args.getOrNull(1) ?: "64").toInt()
	val iterations = (args.getOrNull(2) ?: "40").toInt()
	val delayMs = (args.getOrNull(3) ?: "20").toLong()
	val hits = (args.getOrNull(4) ?: "50").toInt()

	val server = startStub(delayMs, hits)
	val port = server.address.port

	val mapper = ObjectMapper()
		.registerModule(KotlinModule.Builder().build())
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

	val maxConn = (args.getOrNull(5) ?: "0").toInt()
	val restClient = Rest5Client.builder(HttpHost("http", "127.0.0.1", port))
		.apply {
			if (maxConn > 0) {
				setConnectionManagerCallback { cm ->
					cm.setMaxConnPerRoute(maxConn)
					cm.setMaxConnTotal(maxConn * 3)
				}
			}
		}
		.build()
	val transport = Rest5ClientTransport(restClient, ThreadRecordingJsonpMapper(JacksonJsonpMapper(mapper)))
	val sync = ElasticsearchClient(transport)
	val asyncClient = ElasticsearchAsyncClient(transport)

	val decodeThreads = ConcurrentHashMap<String, Int>()

	val realMode = mode.removePrefix("cancel-")

	suspend fun oneRequest(index: String = "slow") {
		when (realMode) {
			"sync" -> withContext(Dispatchers.IO) {
				sync.search({ s -> s.index(index).size(hits) }, SearchDoc::class.java)
				val n = Thread.currentThread().name.substringBeforeLast('-')
				decodeThreads.merge(n, 1, Int::plus)
			}
			"async" -> {
				asyncClient.search({ s -> s.index(index).size(hits) }, SearchDoc::class.java).await()
				val n = Thread.currentThread().name.substringBeforeLast('-')
				decodeThreads.merge(n, 1, Int::plus)
			}
			else -> error("unknown mode $realMode")
		}
	}

	if (mode.startsWith("cancel-")) {
		runBlocking { repeat(3) { oneRequest("fast") } }
		val poolSize = if (maxConn > 0) maxConn else 10
		println("=".repeat(72))
		println("CANCELLATION PROBE  mode=$realMode  slowDelay=${delayMs}ms  pool=$poolSize")
		runBlocking {
			val phase1 = System.nanoTime()
			coroutineScope {
				(1..poolSize).map {
					async {
						try {
							kotlinx.coroutines.withTimeout(100) { oneRequest("slow") }
						} catch (_: Exception) {
						}
					}
				}.awaitAll()
			}
			val p1 = (System.nanoTime() - phase1) / 1_000_000
			println("phase 1: $poolSize requests with withTimeout(100ms) against a ${delayMs}ms backend")
			println("         -> returned after ${p1}ms   (100ms = timeout honoured; ~${delayMs}ms = timeout NOT honoured)")

			val phase2 = System.nanoTime()
			oneRequest("fast")
			val p2 = (System.nanoTime() - phase2) / 1_000_000
			println("phase 2: one request to the FAST (0ms) index right after the timeouts")
			println("         -> completed in ${p2}ms   (small = pool free; large = pool still held by cancelled work)")
			println("in-flight still at server: ${inFlight.get()}")
		}
		println("=".repeat(72))
		restClient.close()
		server.stop(0)
		exitProcess(0)
	}

	// warmup
	runBlocking {
		coroutineScope {
			(1..16).map { async { repeat(10) { oneRequest() } } }.awaitAll()
		}
	}
	inFlight.set(0)
	maxInFlight.set(0)
	decodeThreads.clear()
	decodeThreadGroups.clear()

	val peakThreads = ConcurrentHashMap<String, Int>()
	val sampler = Thread {
		try {
			while (!Thread.currentThread().isInterrupted) {
				for ((k, v) in threadHistogram()) peakThreads.merge(k, v) { a, b -> maxOf(a, b) }
				Thread.sleep(5)
			}
		} catch (_: InterruptedException) {
		}
	}
	sampler.isDaemon = true
	sampler.start()

	val latencies = LongArray(concurrency * iterations)
	val cursor = AtomicInteger()
	val wallStart = System.nanoTime()
	runBlocking {
		coroutineScope {
			(1..concurrency).map {
				async {
					repeat(iterations) {
						val t0 = System.nanoTime()
						oneRequest()
						latencies[cursor.getAndIncrement()] = (System.nanoTime() - t0) / 1000
					}
				}
			}.awaitAll()
		}
	}
	val wallMs = (System.nanoTime() - wallStart) / 1_000_000
	sampler.interrupt()

	latencies.sort()
	val total = latencies.size
	println("=".repeat(72))
	println(
		"mode=$mode concurrency=$concurrency iterations=$iterations serverDelay=${delayMs}ms hits=$hits " +
			"maxConnPerRoute=${if (maxConn > 0) maxConn.toString() else "10(default)"}",
	)
	println("requests=$total wall=${wallMs}ms throughput=${"%.0f".format(total * 1000.0 / wallMs)} req/s")
	println(
		"latency us  p50=${percentile(latencies, 50.0)}  p90=${percentile(latencies, 90.0)}  " +
			"p99=${percentile(latencies, 99.0)}  max=${latencies.last()}",
	)
	println(
		"latency ms  p50=${"%.1f".format(percentile(latencies, 50.0) / 1000.0)}  " +
			"p99=${"%.1f".format(percentile(latencies, 99.0) / 1000.0)}",
	)
	println("max concurrent in-flight AT SERVER = ${maxInFlight.get()}   (== ES connection pool ceiling)")
	println("peak thread counts:")
	peakThreads.entries.filter { !it.key.startsWith("other:") }.sortedByDescending { it.value }
		.forEach { println("   ${it.value.toString().padStart(4)}  ${it.key}") }
	println("coroutine RESUMES on: ${decodeThreads.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key}=${it.value}" }}")
	println("JSON DECODE runs on (measured inside JsonpMapper):")
	decodeThreadGroups.entries.sortedByDescending { it.value }.take(4)
		.forEach { println("   ${it.value.toString().padStart(7)}  ${it.key}") }
	println("=".repeat(72))

	restClient.close()
	server.stop(0)
	exitProcess(0)
}
