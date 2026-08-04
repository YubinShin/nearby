package dev.yubin.search.startup

import co.elastic.clients.elasticsearch.ElasticsearchClient
import dev.yubin.search.core.analysis.AnalyzerFingerprint
import dev.yubin.search.core.embed.EmbeddingModel
import dev.yubin.search.core.meta.IndexMeta
import dev.yubin.search.core.meta.IndexMetaStore
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class IndexContractGuard(
	private val meta: IndexMetaStore,
	private val es: ElasticsearchClient,

	private val embeddings: ObjectProvider<EmbeddingModel>,
	@Value("\${psp.index.search-alias}") private val searchAlias: String,
	@Value("\${psp.index.suggest-alias}") private val suggestAlias: String,
) {
	@PostConstruct
	fun verify() {
		meta.requireCompatible(
			IndexMeta.PIPELINE_SEARCH,
			stampOf(searchAlias, AnalyzerFingerprint.SEARCH_ANALYZER),
			remedy = REMEDY_KEYWORD,
		)
		meta.requireCompatible(
			IndexMeta.PIPELINE_SUGGEST,
			stampOf(suggestAlias, AnalyzerFingerprint.SUGGEST_ANALYZER),
			remedy = REMEDY_KEYWORD,
		)

		embeddings.ifAvailable { model ->
			meta.requireCompatible(
				IndexMeta.PIPELINE_VECTOR,
				IndexMeta.stamp(embeddingModel = model.modelId, embeddingDim = model.dimension),
				remedy = REMEDY_VECTOR,
			)
		}

		log.info("index contract verified — schema v{}", IndexMeta.SCHEMA_VERSION)
	}

	private fun stampOf(alias: String, analyzer: String) =
		IndexMeta.stamp(analyzerFingerprint = AnalyzerFingerprint.of(es, alias, analyzer))

	private companion object {
		const val REMEDY_KEYWORD =
			"색인기(indexer-batch)에서 POST /admin/reindex 로 전체 재색인한 뒤 다시 띄우세요. " +
				"색인기와 질의기의 배포 버전이 어긋났거나, 형태소 사전을 바꾸고 재색인을 빠뜨렸을 때 납니다."

		const val REMEDY_VECTOR =
			"색인기(indexer-batch)에서 POST /admin/vector/reindex 로 전체 재색인한 뒤 다시 띄우세요. " +
				"임베딩 모델이 다르면 유사도 점수가 의미를 잃습니다 (ADR 0010)."

		val log = LoggerFactory.getLogger(IndexContractGuard::class.java)
	}
}
