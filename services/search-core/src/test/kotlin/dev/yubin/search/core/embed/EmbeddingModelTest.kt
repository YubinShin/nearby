package dev.yubin.search.core.embed

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 임베딩 모델이 **실제로 한국어 뜻을 잡는지** 재는 테스트.
 *
 * 설정 실수(접두어 누락·정규화 꺼짐·풀링 방식 오지정)는 예외를 던지지 않고 **품질만 조용히**
 * 떨어뜨린다. 그래서 "돌아간다"가 아니라 숫자로 확인한다:
 * 길이가 1인지, 뜻이 가까운 쌍이 먼 쌍보다 점수가 높은지.
 *
 * 모델 파일(470MB)이 없으면 통째로 건너뛴다 — 저장소만 받은 사람의 빌드를 깨지 않기 위해서.
 */
@EnabledIf("modelPresent")
class EmbeddingModelTest {

	@Test
	fun `벡터는 384차원이고 길이가 1이다`() = runTest {
		val v = model.embedQuery("강남 브런치 카페")
		assertEquals(384, v.size)
		val norm = Math.sqrt(v.sumOf { (it * it).toDouble() })
		assertTrue(abs(norm - 1.0) < 1e-3, "정규화가 안 됨: |v| = $norm")
	}

	@Test
	fun `뜻이 가까운 장소가 먼 장소보다 점수가 높다`() = runTest {
		val q = model.embedQuery("회 먹을 데")
		val (near, far) = model.embedPassages(
			listOf(
				"오라이횟집. 한식 횟집. 강남구 역삼동",
				"강남필라테스. 스포츠클럽 필라테스. 강남구 역삼동",
			),
		)
		val nearScore = cosine(q, near)
		val farScore = cosine(q, far)
		assertTrue(nearScore > farScore, "횟집 $nearScore <= 필라테스 $farScore")
	}

	@Test
	fun `같은 문장은 항상 같은 벡터가 된다`() = runTest {
		// 색인 때와 검색 때 결과가 흔들리면 유사도 자체가 무의미해진다.
		assertTrue(model.embedQuery("스타벅스").contentEquals(model.embedQuery("스타벅스")))
	}

	@Test
	fun `질의 접두어와 문서 접두어는 다른 벡터를 만든다`() = runTest {
		// e5 의 접두어 규칙이 실제로 먹고 있는지 확인 — 안 먹으면 두 벡터가 같아진다.
		val asQuery = model.embedQuery("카페")
		val asPassage = model.embedPassages(listOf("카페")).single()
		assertTrue(!asQuery.contentEquals(asPassage))
		// 그래도 같은 단어라 뜻은 가까워야 한다.
		assertTrue(cosine(asQuery, asPassage) > 0.8, "접두어만 다른데 뜻이 멀다: ${cosine(asQuery, asPassage)}")
	}

	private fun cosine(a: FloatArray, b: FloatArray): Double =
		a.indices.sumOf { (a[it] * b[it]).toDouble() }   // 정규화돼 있으므로 내적 = 코사인

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
