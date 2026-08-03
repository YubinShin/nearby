package dev.yubin.search.core.meta

import dev.yubin.search.core.es.EsJsonpMapperConfig
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndexMetaStampDeserializationTest {
	private val mapper = EsJsonpMapperConfig().jsonpMapper()

	private fun parse(source: String): IndexMeta.Stamp {
		val parser = mapper.jsonProvider().createParser(ByteArrayInputStream(source.toByteArray()))
		return mapper.deserialize(parser, IndexMeta.Stamp::class.java)
	}

	@Test
	fun `a stamp written before fingerprints existed deserializes with a null fingerprint`() {
		val stamp = parse("""{"schema_version":2,"embedding_model":null,"embedding_dim":null}""")

		assertEquals(2, stamp.schema_version)
		assertNull(stamp.analyzer_fingerprint)
	}

	@Test
	fun `such a stamp does not block a querier that computes a fingerprint`() {
		val indexed = parse("""{"schema_version":${IndexMeta.SCHEMA_VERSION}}""")

		assertEquals(
			IndexMeta.Verdict.Ok,
			IndexMeta.verify(indexed, IndexMeta.stamp(analyzerFingerprint = "6985af8f19f6")),
		)
	}

	@Test
	fun `a stamp carrying a fingerprint round-trips`() {
		val stamp = parse(
			"""{"schema_version":2,"embedding_model":"multilingual-e5-small","embedding_dim":384,""" +
				""""analyzer_fingerprint":"6985af8f19f6"}""",
		)

		assertEquals("multilingual-e5-small", stamp.embedding_model)
		assertEquals(384, stamp.embedding_dim)
		assertEquals("6985af8f19f6", stamp.analyzer_fingerprint)
	}
}