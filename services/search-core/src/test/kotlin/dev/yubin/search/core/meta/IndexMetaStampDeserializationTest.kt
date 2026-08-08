package dev.yubin.search.core.meta

import dev.yubin.search.core.es.EsJsonpMapperConfig
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IndexMetaStampDeserializationTest {
	private val mapper = EsJsonpMapperConfig().jsonpMapper()

	private fun parse(source: String): IndexMeta.Stamp {
		val parser = mapper.jsonProvider().createParser(ByteArrayInputStream(source.toByteArray()))
		return mapper.deserialize(parser, IndexMeta.Stamp::class.java)
	}

	@Test
	fun `a stamp written when the schema version was still a field deserializes without it`() {
		val stamp = parse("""{"schema_version":3,"embedding_model":null,"embedding_dim":null}""")

		assertNull(stamp.document_fingerprint)
		assertNull(stamp.analyzer_fingerprint)
	}

	@Test
	fun `such a stamp blocks a querier that computes a document fingerprint`() {
		val indexed = parse("""{"schema_version":3}""")
		val verdict = IndexMeta.verify(indexed, IndexMeta.stamp(documentFingerprint = "6985af8f19f6"))

		assertIs<IndexMeta.Verdict.Mismatch>(verdict)
	}

	@Test
	fun `a stamp carrying every fingerprint round-trips`() {
		val stamp = parse(
			"""{"document_fingerprint":"c0ffee001122","brand_fingerprint":"beef00334455",""" +
				""""embedding_model":"multilingual-e5-small","embedding_dim":384,""" +
				""""analyzer_fingerprint":"6985af8f19f6"}""",
		)

		assertEquals("c0ffee001122", stamp.document_fingerprint)
		assertEquals("beef00334455", stamp.brand_fingerprint)
		assertEquals("multilingual-e5-small", stamp.embedding_model)
		assertEquals(384, stamp.embedding_dim)
		assertEquals("6985af8f19f6", stamp.analyzer_fingerprint)
	}
}
