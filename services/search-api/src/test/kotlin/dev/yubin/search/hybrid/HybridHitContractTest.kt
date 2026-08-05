package dev.yubin.search.hybrid

import dev.yubin.search.query.PlaceHit
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertTrue

class HybridHitContractTest {
	@Test
	fun `the hsearch hit carries every field the ask-api answer stage renders`() {
		val json = ObjectMapper().writeValueAsString(hit())

		assertTrue("먹어도" in json, "the hit must actually serialise before the field check means anything: $json")
		ANSWER_RENDER_FIELDS.forEach { field ->
			assertTrue(
				"\"$field\"" in json,
				"ask-api HsearchContract renders '$field' — renaming it silently thins the grounded answer " +
					"(docs/api-spec.md, ADR 0015 gap 5). Change both sides together: $json",
			)
		}
	}

	@Test
	fun `the fields the answer cites are unwrapped to the top level of the hit`() {
		val json = ObjectMapper().writeValueAsString(hit())

		assertTrue("\"place\"" !in json, "PlaceHit stays unwrapped — ask-api reads hits[].placeId, not hits[].place.placeId: $json")
	}

	private fun hit() = HybridHit(
		place = PlaceHit(
			placeId = "MA010120220810147236",
			name = "먹어도",
			branch = null,
			category = "횟집",
			address = "서울특별시 강남구 학동로56길 32",
			sigungu = "강남구",
			dong = "삼성2동",
			lat = 37.51518,
			lon = 127.04282,
			score = 0.01639,
		),
		ranks = mapOf("vector" to 1),
		scores = mapOf("vector" to 0.872),
	)

	private companion object {
		val ANSWER_RENDER_FIELDS = listOf("placeId", "name", "category", "dong", "address")
	}
}
