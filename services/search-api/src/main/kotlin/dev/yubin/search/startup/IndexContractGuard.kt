package dev.yubin.search.startup

import dev.yubin.search.core.embed.EmbeddingModel
import dev.yubin.search.core.meta.IndexMeta
import dev.yubin.search.core.meta.IndexMetaStore
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@Component
class IndexContractGuard(
	private val meta: IndexMetaStore,

	private val embeddings: ObjectProvider<EmbeddingModel>,
) {
	@PostConstruct
	fun verify() {
		val keyword = IndexMeta.stamp()
		meta.requireCompatible(IndexMeta.PIPELINE_SEARCH, keyword, remedy = REMEDY_KEYWORD)
		meta.requireCompatible(IndexMeta.PIPELINE_SUGGEST, keyword, remedy = REMEDY_KEYWORD)

		embeddings.ifAvailable { model ->
			meta.requireCompatible(
				IndexMeta.PIPELINE_VECTOR,
				IndexMeta.stamp(embeddingModel = model.modelId, embeddingDim = model.dimension),
				remedy = REMEDY_VECTOR,
			)
		}

		log.info("색인 계약 확인 완료 — 스키마 v{}", IndexMeta.SCHEMA_VERSION)
	}

	private companion object {
		const val REMEDY_KEYWORD =
			"색인기(indexer-batch)에서 POST /admin/reindex 로 전체 재색인한 뒤 다시 띄우세요. " +
				"색인기와 질의기의 배포 버전이 어긋나 있을 수도 있습니다."

		const val REMEDY_VECTOR =
			"색인기(indexer-batch)에서 POST /admin/vector/rebuild 로 전체 재색인한 뒤 다시 띄우세요. " +
				"임베딩 모델이 다르면 유사도 점수가 의미를 잃습니다 (ADR 0010)."

		val log = LoggerFactory.getLogger(IndexContractGuard::class.java)
	}
}
