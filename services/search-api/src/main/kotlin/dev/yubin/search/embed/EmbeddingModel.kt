package dev.yubin.search.embed

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory
import ai.djl.inference.Predictor
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 문장을 벡터로 바꾼다 — 뜻으로 찾기(5단계)의 재료 (ADR 0010).
 *
 * ### 색인기와 질의기가 같은 이 클래스를 쓴다 (핵심)
 * 문서 벡터와 질의 벡터는 **같은 모델·같은 전처리**로 만들어야 한다. 다르면 두 벡터가 서로 다른
 * 공간에 놓여 유사도가 의미를 잃는다. ADR 0008 에서 배운 "색인과 검색이 같은 사전을 봐야 한다"와
 * 정확히 같은 함정이라, 아예 추론 서버를 분리하지 않고 한 클래스로 묶어 코드로 강제한다.
 *
 * ### e5 계열의 접두어 규칙
 * 이 모델(multilingual-e5-small)은 학습할 때 문장 앞에 `query: ` / `passage: ` 를 붙였다.
 * 추론에서도 똑같이 붙여야 성능이 나온다 — 빼먹으면 조용히 품질만 나빠지는 종류의 실수라
 * 호출부가 실수할 수 없게 [embedQuery] / [embedPassages] 로 갈라두었다.
 *
 * ### 스레드 처리
 * DJL `Predictor` 는 스레드 안전하지 않다. 모델(ONNX 세션)은 공유하고 Predictor 만 풀로 돌린다.
 * 추론은 CPU 를 태우는 일이라 [Dispatchers.Default] 에서 돌리고, 풀 크기만큼만 동시에 들어가도록
 * 코루틴 세마포어로 막는다 (IO 디스패처를 쓰면 CPU 코어 수보다 많은 추론이 몰려 서로 느려진다).
 */
@Component
@ConditionalOnProperty(prefix = "psp.vector", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EmbeddingModel(
	@Value("\${psp.embedding.model-dir}") modelDir: String,
	@Value("\${psp.embedding.max-tokens}") maxTokens: Int,
	@Value("\${psp.embedding.pool-size}") private val poolSize: Int,
) {

	private val model: ZooModel<String, FloatArray>
	private val idle = ConcurrentLinkedQueue<Predictor<String, FloatArray>>()
	private val permits = Semaphore(poolSize)

	// 스프링 all-open 플러그인이 @Component 의 멤버를 open 으로 바꾸므로, init 에서 채우는
	// 프로퍼티는 final 을 명시해야 한다.
	/** 벡터 차원. Qdrant 컬렉션 설정과 반드시 같아야 해서 모델에서 직접 읽어 쓴다. */
	final val dimension: Int

	init {
		val dir = resolveModelDir(modelDir)
		val started = System.nanoTime()

		model = Criteria.builder()
			.setTypes(String::class.java, FloatArray::class.java)
			.optModelPath(dir)
			// 파일명이 model.onnx 라 폴더명(multilingual-e5-small)과 다르다 — 명시해준다.
			.optModelName(MODEL_FILE)
			.optEngine("OnnxRuntime")
			.optTranslatorFactory(TextEmbeddingTranslatorFactory())
			.optArguments(
				mapOf(
					// 마지막 층 토큰 벡터들의 평균 = 문장 벡터. e5 가 학습에 쓴 방식.
					"pooling" to "mean",
					// 길이를 1로 맞춰두면 코사인 유사도 = 내적이 되어 계산·비교가 단순해진다.
					"normalize" to "true",
					// 상호명·카테고리는 짧다. 512 를 다 쓰면 배치 추론이 그만큼 느려진다.
					"maxLength" to maxTokens,
					"padding" to "true",
					// 이 ONNX 그래프는 입력을 셋(input_ids·attention_mask·token_type_ids) 요구한다.
					// 기본값은 token_type_ids 를 안 만들어서 "Input mismatch" 로 죽는다.
					"includeTokenTypes" to "true",
				),
			)
			.build()
			.loadModel()

		repeat(poolSize) { idle.add(model.newPredictor()) }
		dimension = model.newPredictor().use { it.predict("차원 확인").size }

		log.info(
			"임베딩 모델 로드: {} (차원 {}, 최대 {}토큰, 추론기 {}개, {}ms)",
			dir, dimension, maxTokens, poolSize, (System.nanoTime() - started) / 1_000_000,
		)
	}

	/** 사용자가 친 검색어 → 벡터. */
	suspend fun embedQuery(text: String): FloatArray = embed(listOf(QUERY_PREFIX + text)).first()

	/** 색인할 장소 설명문들 → 벡터. 배치로 넣어야 추론이 빠르다. */
	suspend fun embedPassages(texts: List<String>): List<FloatArray> =
		if (texts.isEmpty()) emptyList() else embed(texts.map { PASSAGE_PREFIX + it })

	private suspend fun embed(prefixed: List<String>): List<FloatArray> = permits.withPermit {
		val predictor = idle.poll() ?: model.newPredictor()
		try {
			withContext(Dispatchers.Default) { predictor.batchPredict(prefixed) }
		} finally {
			idle.offer(predictor)
		}
	}

	@PreDestroy
	fun close() {
		idle.forEach { it.close() }
		model.close()
	}

	/**
	 * 모델 폴더 찾기. 설정값이 상대경로면 **현재 폴더에서 위로 올라가며** 찾는다.
	 * 앱을 저장소 루트에서 띄우든 `services/search-api` 에서 띄우든 같은 설정으로 돌게 하려는 것 —
	 * 안 그러면 "왜 안 뜨지"의 절반이 이 경로 문제가 된다.
	 */
	private fun resolveModelDir(configured: String): Path {
		val given = Path.of(configured)
		if (given.isAbsolute) return given.also { require(Files.isDirectory(it)) { missing(it) } }

		var base = Path.of("").toAbsolutePath()
		repeat(MAX_PARENT_LOOKUPS) {
			val candidate = base.resolve(given)
			if (Files.isDirectory(candidate)) return candidate.normalize()
			base = base.parent ?: return@repeat
		}
		throw IllegalStateException(missing(Path.of("").toAbsolutePath().resolve(given)))
	}

	private fun missing(path: Path) =
		"임베딩 모델을 찾을 수 없습니다: $path\n" +
			"  ./scripts/fetch_embedding_model.sh 로 내려받거나, PSP_MODEL_DIR 로 경로를 알려주세요."

	companion object {
		/** e5 계열이 학습 때 쓴 접두어. 바꾸면 품질이 조용히 나빠진다. */
		const val QUERY_PREFIX = "query: "
		const val PASSAGE_PREFIX = "passage: "

		private const val MODEL_FILE = "model.onnx"
		private const val MAX_PARENT_LOOKUPS = 4
		private val log = LoggerFactory.getLogger(EmbeddingModel::class.java)
	}
}
