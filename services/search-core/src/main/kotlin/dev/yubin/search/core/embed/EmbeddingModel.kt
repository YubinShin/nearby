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

@Component
@ConditionalOnProperty(prefix = "psp.vector", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class EmbeddingModel(
	@Value("\${psp.embedding.model-dir}") modelDir: String,
	@Value("\${psp.embedding.max-tokens}") maxTokens: Int,

	@Value("\${psp.embedding.pool-size}") final val poolSize: Int,
) {
	private val model: ZooModel<String, FloatArray>

	private val idle: ArrayBlockingQueue<Predictor<String, FloatArray>> = ArrayBlockingQueue(poolSize)

	final val dimension: Int

	final val modelId: String

	init {
		val dir = resolveModelDir(modelDir)
		modelId = dir.fileName.toString()
		val started = System.nanoTime()

		model = Criteria.builder()
			.setTypes(String::class.java, FloatArray::class.java)
			.optModelPath(dir)

			.optModelName(MODEL_FILE)
			.optEngine("OnnxRuntime")
			.optTranslatorFactory(TextEmbeddingTranslatorFactory())
			.optArguments(
				mapOf(

					"pooling" to "mean",

					"normalize" to "true",

					"maxLength" to maxTokens,
					"padding" to "true",

					"includeTokenTypes" to "true",
				),
			)
			.build()
			.loadModel()

		repeat(poolSize) { idle.put(model.newPredictor()) }

		dimension = model.newPredictor().use { it.predict("차원 확인").size }

		log.info(
			"embedding model loaded: {} ({} dims, max {} tokens, {} predictors, {}ms)",
			dir, dimension, maxTokens, poolSize, (System.nanoTime() - started) / 1_000_000,
		)
	}

	fun embedQuery(text: String): FloatArray = embed(listOf(QUERY_PREFIX + text)).first()

	fun embedPassages(texts: List<String>): List<FloatArray> =
		if (texts.isEmpty()) emptyList() else embed(texts.map { PASSAGE_PREFIX + it })

	private fun embed(prefixed: List<String>): List<FloatArray> {
		val predictor = idle.take()
		return try {
			predictor.batchPredict(prefixed)
		} finally {
			idle.put(predictor)
		}
	}

	@PreDestroy
	fun close() {
		idle.forEach { it.close() }
		model.close()
	}

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
		"embedding model not found: $path\n" +
			"  download it with ./scripts/fetch_embedding_model.sh, or point PSP_MODEL_DIR at its directory."

	companion object {
		const val QUERY_PREFIX = "query: "
		const val PASSAGE_PREFIX = "passage: "

		private const val MODEL_FILE = "model.onnx"
		private const val MAX_PARENT_LOOKUPS = 4
		private val log = LoggerFactory.getLogger(EmbeddingModel::class.java)
	}
}
