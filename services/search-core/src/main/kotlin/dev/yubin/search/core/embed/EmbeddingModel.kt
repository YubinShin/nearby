package dev.yubin.search.core.embed

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory
import ai.djl.inference.Predictor
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue

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
 * ### 스레드 처리 — **이 클래스는 블로킹이다** (ADR 0013)
 * 추론은 ONNX 네이티브 호출이라 **본질적으로 블로킹**이다. 전에는 이 클래스가 `suspend` 였는데,
 * 그건 블로킹 호출을 `withContext` 로 감싸 스레드를 옮긴 것뿐이었다 — 안 기다리게 만든 게 아니라
 * **누가 기다릴지를 여기서 정해버린 것**이다. 그래서 배치 색인기는 쓰지도 않는 코루틴을 짊어졌다.
 *
 * 지금은 **어느 스레드에서 돌릴지를 호출자가 고른다.**
 * - `indexer-batch` → Batch 의 job 스레드에서 그냥 부른다 (동시성 1, 대기 자체가 없다).
 * - `search-api` → `Dispatchers.Default.limitedParallelism(poolSize)` 에서 부른다. 대기가
 *   **스레드 블로킹이 아니라 코루틴 서스펜션**으로 일어나 이벤트 루프가 멀쩡하다. 예전 코루틴
 *   세마포어가 하던 일이 그 디스패처로 옮겨간 것이다.
 *
 * DJL `Predictor` 는 스레드 안전하지 않다. 모델(ONNX 세션)은 공유하고 Predictor 만 풀로 돌린다.
 * 풀을 **블로킹 큐**로 두면 "동시 추론은 [poolSize] 개까지"라는 규칙이 자료구조 하나에 다 들어간다
 * (전에는 세마포어 + 큐 두 곳에 흩어져 있었다).
 */
@Component
@ConditionalOnProperty(prefix = "psp.vector", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EmbeddingModel(
	@Value("\${psp.embedding.model-dir}") modelDir: String,
	@Value("\${psp.embedding.max-tokens}") maxTokens: Int,
	/**
	 * 동시에 추론에 들어갈 수 있는 수. **호출자가 자기 동시성을 여기에 맞추기 위해 읽는다** —
	 * `search-api` 가 `limitedParallelism(poolSize)` 를 만들 때 쓴다. 그래서 public 이다.
	 */
	@Value("\${psp.embedding.pool-size}") final val poolSize: Int,
) {

	private val model: ZooModel<String, FloatArray>

	/**
	 * 유휴 Predictor 풀. [poolSize] 개를 미리 만들어 채우고 [ArrayBlockingQueue.take] 로 꺼낸다 —
	 * 비어 있으면 **기다린다.** 이 큐가 곧 동시성 제한이라, 예전처럼 "없으면 하나 더 만들기"
	 * 우회 경로가 없다(그 경로는 풀이 조용히 커질 여지였다).
	 */
	private val idle: ArrayBlockingQueue<Predictor<String, FloatArray>> = ArrayBlockingQueue(poolSize)

	// 스프링 all-open 플러그인이 @Component 의 멤버를 open 으로 바꾸므로, init 에서 채우는
	// 프로퍼티는 final 을 명시해야 한다.
	/** 벡터 차원. Qdrant 컬렉션 설정과 반드시 같아야 해서 모델에서 직접 읽어 쓴다. */
	final val dimension: Int

	/**
	 * 실제로 읽은 모델의 이름(폴더명). 설정값이 아니라 **로드된 결과**라는 게 중요하다 —
	 * 색인기와 질의기가 같은 모델을 쓰는지 대조할 때 쓴다 (`IndexMeta`, ADR 0011).
	 */
	final val modelId: String

	init {
		val dir = resolveModelDir(modelDir)
		modelId = dir.fileName.toString()
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

		repeat(poolSize) { idle.put(model.newPredictor()) }
		// 풀에서 빌리지 않고 따로 만들어 쓰고 닫는다 — 기동 중이라 경쟁이 없고, 풀을 건드리지 않는다.
		dimension = model.newPredictor().use { it.predict("차원 확인").size }

		log.info(
			"임베딩 모델 로드: {} (차원 {}, 최대 {}토큰, 추론기 {}개, {}ms)",
			dir, dimension, maxTokens, poolSize, (System.nanoTime() - started) / 1_000_000,
		)
	}

	/** 사용자가 친 검색어 → 벡터. **블로킹** — 호출자가 알맞은 스레드에서 부를 것. */
	fun embedQuery(text: String): FloatArray = embed(listOf(QUERY_PREFIX + text)).first()

	/** 색인할 장소 설명문들 → 벡터. 배치로 넣어야 추론이 빠르다. **블로킹.** */
	fun embedPassages(texts: List<String>): List<FloatArray> =
		if (texts.isEmpty()) emptyList() else embed(texts.map { PASSAGE_PREFIX + it })

	private fun embed(prefixed: List<String>): List<FloatArray> {
		// 풀이 비어 있으면 여기서 기다린다. 호출자가 동시성을 poolSize 에 맞춰 두면 사실상 안 기다린다.
		val predictor = idle.take()
		return try {
			predictor.batchPredict(prefixed)
		} finally {
			// 꺼낸 건 반드시 돌려놓는다 — 못 돌려놓으면 풀이 영구히 줄어든다(느려지기만 하고 조용하다).
			idle.put(predictor)
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
