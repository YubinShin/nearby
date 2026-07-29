package dev.yubin.search.core.embed

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIf("modelPresent")
class EmbeddingModelTest {
	@Test
	fun `the vector is 384 dimensional and unit length`() {
		val v = model.embedQuery("강남 브런치 카페")
		assertEquals(384, v.size)
		val norm = Math.sqrt(v.sumOf { (it * it).toDouble() })
		assertTrue(abs(norm - 1.0) < 1e-3, "not normalized: |v| = $norm")
	}

	@Test
	fun `a semantically closer place scores higher than a distant one`() {
		val q = model.embedQuery("회 먹을 데")
		val (near, far) = model.embedPassages(
			listOf(
				"오라이횟집. 한식 횟집. 강남구 역삼동",
				"강남필라테스. 스포츠클럽 필라테스. 강남구 역삼동",
			),
		)
		val nearScore = cosine(q, near)
		val farScore = cosine(q, far)
		assertTrue(nearScore > farScore, "sashimi $nearScore <= pilates $farScore")
	}

	@Test
	fun `the same sentence always becomes the same vector`() {
		assertTrue(model.embedQuery("스타벅스").contentEquals(model.embedQuery("스타벅스")))
	}

	@Test
	fun `the query prefix and the passage prefix produce different vectors`() {
		val asQuery = model.embedQuery("카페")
		val asPassage = model.embedPassages(listOf("카페")).single()
		assertTrue(!asQuery.contentEquals(asPassage))

		assertTrue(cosine(asQuery, asPassage) > 0.8, "only the prefix differs but the meanings are far apart: ${cosine(asQuery, asPassage)}")
	}

	private fun cosine(a: FloatArray, b: FloatArray): Double =
		a.indices.sumOf { (a[it] * b[it]).toDouble() }

	companion object {
		private const val MODEL_DIR = "models/multilingual-e5-small"

		private lateinit var model: EmbeddingModel

		@JvmStatic
		fun modelPresent(): Boolean = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
			.take(4)
			.any { java.nio.file.Files.isDirectory(it.resolve("$MODEL_DIR")) }

		@JvmStatic
		@BeforeAll
		fun load() {
			model = EmbeddingModel(MODEL_DIR, maxTokens = 64, poolSize = 1)
		}

		@JvmStatic
		@AfterAll
		fun unload() {
			if (::model.isInitialized) model.close()
		}
	}
}
