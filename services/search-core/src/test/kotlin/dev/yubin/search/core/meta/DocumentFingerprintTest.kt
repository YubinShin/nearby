package dev.yubin.search.core.meta

import dev.yubin.search.core.place.PlaceDocuments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DocumentFingerprintTest {
	@Test
	fun `the same code digests to the same value`() {
		assertEquals(DocumentFingerprint.search(), DocumentFingerprint.search())
		assertEquals(DocumentFingerprint.suggest(), DocumentFingerprint.suggest())
		assertEquals(DocumentFingerprint.vector(), DocumentFingerprint.vector())
	}

	@Test
	fun `each pipeline has its own fingerprint`() {
		val fingerprints = listOf(
			DocumentFingerprint.search(),
			DocumentFingerprint.suggest(),
			DocumentFingerprint.vector(),
		)
		assertEquals(3, fingerprints.distinct().size, fingerprints.toString())
	}

	@Test
	fun `the fingerprint is twelve hex characters`() {
		val digest = DocumentFingerprint.search()
		assertEquals(12, digest.length, digest)
		assertTrue(digest.all { it in "0123456789abcdef" }, digest)
	}

	@Test
	fun `a renamed field changes the fingerprint`() {
		val probe = DocumentFingerprint.PROBE.first()
		val doc = PlaceDocuments.searchDoc(probe)
		val renamed = doc.mapKeys { (k, _) -> if (k == "brand_text") "brandText" else k }

		assertNotEquals(
			DocumentFingerprint.render(probe, doc),
			DocumentFingerprint.render(probe, renamed),
		)
	}

	@Test
	fun `the probe covers rows the indexer refuses to index`() {
		assertTrue(DocumentFingerprint.PROBE.any { it.deletedAt != null })
		assertTrue(DocumentFingerprint.PROBE.any { it.duplicateOf != null })
		assertTrue(DocumentFingerprint.PROBE.any { !it.indexable })
	}

	@Test
	fun `the indexable rule is part of what the fingerprint digests`() {
		val alive = DocumentFingerprint.PROBE.first { it.indexable }
		val judged = alive.copy(duplicateOf = "probe-other")

		assertNotEquals(
			DocumentFingerprint.render(alive, PlaceDocuments.searchDoc(alive)),
			DocumentFingerprint.render(judged, PlaceDocuments.searchDoc(judged)),
		)
	}

	@Test
	fun `the probe rows reach the brand dictionary`() {
		val fromDictionary = PlaceDocuments.searchDoc(DocumentFingerprint.PROBE[0])
		val fromSource = PlaceDocuments.searchDoc(DocumentFingerprint.PROBE[1])

		assertEquals("CU", fromDictionary["brand"])
		assertEquals("스타벅스", fromSource["brand"])
	}

	@Test
	fun `a probe row without optional fields still produces a document`() {
		val minimal = PlaceDocuments.searchDoc(DocumentFingerprint.PROBE[2])

		assertEquals("혼밥대왕", minimal["name"])
		assertTrue("brand" !in minimal)
		assertTrue("location" !in minimal)
	}
}
