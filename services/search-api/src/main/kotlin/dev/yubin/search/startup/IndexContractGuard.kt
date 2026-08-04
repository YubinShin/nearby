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
			"run a full reindex with POST /admin/reindex on the indexer (indexer-batch), then start this app again. " +
				"happens when the indexer and the query app run different builds, or when the morpheme dictionary changed without a reindex."

		const val REMEDY_VECTOR =
			"run a full reindex with POST /admin/vector/reindex on the indexer (indexer-batch), then start this app again. " +
				"a different embedding model makes the similarity scores meaningless (ADR 0010)."

		val log = LoggerFactory.getLogger(IndexContractGuard::class.java)
	}
}
