package dev.yubin.search.startup

import dev.yubin.search.core.embed.EmbeddingModel
import dev.yubin.search.core.meta.IndexMeta
import dev.yubin.search.core.meta.IndexMetaStore
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * 기동할 때 **내가 질의하려는 데이터가 나와 같은 계약으로 만들어졌는지** 확인한다 (ADR 0011).
 * 다르면 이 빈이 예외를 던지고, 스프링 컨텍스트가 뜨지 않는다.
 *
 * ### 왜 '경고'가 아니라 '기동 실패'인가
 * 이 어긋남은 **증상이 없다.** 옛 스키마 위에서 새 질의를 돌리면 예외 대신 0건이 나오고,
 * 다른 모델로 만든 벡터에 질의하면 유사도 숫자는 멀쩡히 나오는데 의미만 없다. 로그도 깨끗하다.
 * 헬스체크는 초록이고 지표도 정상인데 검색 결과만 조용히 틀린 상태 — 이게 제일 늦게 발견된다.
 *
 * 뜨지 않으면 배포가 즉시 실패한다. **시끄럽게 틀리는 쪽이 조용히 틀리는 쪽보다 낫다.**
 *
 * ### Qdrant 차원은 왜 따로 안 보나
 * 벡터 차원이 어긋나면 Qdrant 가 질의를 **거부한다** — 조용히 틀리는 게 아니라 시끄럽게
 * 실패한다. 이 클래스가 맡는 건 엔진이 못 잡아주는 어긋남(모델 정체·스키마 버전)이다.
 */
@Component
class IndexContractGuard(
	private val meta: IndexMetaStore,
	// 벡터 기능을 끄고 뜬 노드에는 이 빈이 없다. 없으면 벡터 계약은 검사하지 않는다.
	private val embeddings: ObjectProvider<EmbeddingModel>,
) {

	/**
	 * 기동 스레드에서 그대로 블로킹한다 — 여기서 기다리는 건 **의도**다. 계약이 확인되기 전에
	 * 컨텍스트가 뜨면 안 된다. (`IndexMetaStore` 가 블로킹이 된 뒤 `runBlocking` 두 겹이 사라졌다.)
	 */
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
