package dev.yubin.search.core.meta

/**
 * **색인된 데이터가 어떤 계약으로 만들어졌는지** 를 데이터 옆에 남겨두는 도장 (ADR 0011).
 *
 * ### 왜 필요한가 — 쪼개면서 잃은 것
 * 전에는 색인기와 질의기가 한 프로세스였다. 그래서 ADR 0010 이 노린 보호("색인과 질의가 같은
 * 모델·같은 전처리를 쓴다")가 **코드로 강제**됐다. 같은 클래스를 쓰니 어긋날 방법이 없었다.
 *
 * 모듈을 쪼개면서 그 보호가 사라졌다. `search-core` 를 공유하는 건 **한 빌드 안에서만** 드리프트를
 * 막는다. 따로 배포된 두 아티팩트는 여전히 어긋날 수 있다 — 색인기만 새 모델로 배포하고 질의기는
 * 옛 버전으로 남아 있는 순간이 실제로 생긴다(롤링 배포, 배포 실패, 롤백).
 *
 * 그리고 그 어긋남은 **예외를 던지지 않는다.** 384차원끼리라면 유사도 계산은 멀쩡히 되고,
 * 숫자도 그럴싸하게 나온다. 다만 그 숫자가 아무 의미가 없을 뿐이다. 로그도 깨끗하다.
 * 이런 종류의 사고는 **뜨지 않는 편이 낫다** — 그래서 질의기는 기동 시 도장을 대조하고,
 * 어긋나면 기동에 실패한다. (셀프 크리틱 #14)
 *
 * ### 왜 ES 문서에 두나
 * Qdrant(1.12)에는 컬렉션에 임의의 메타데이터를 붙일 자리가 없다. 확인해 보니 알 수 없는
 * 필드를 보내면 **오류 없이 조용히 버린다** — 하필 이 기능이 막으려는 바로 그 실패 방식이다.
 * 그래서 ES·Qdrant 양쪽 도장을 ES 문서 한 곳(`psp_index_meta`)에 모은다. 파이프라인마다
 * 한 문서씩, alias 스왑이 **성공한 뒤에** 쓴다 — 실패한 색인의 도장이 남지 않도록.
 */
object IndexMeta {

	/**
	 * 문서 스키마 + ES 매핑의 버전.
	 *
	 * **언제 올리나:** 색인 문서의 필드명이 바뀌거나(`PlaceDocuments`·`PlaceEsDocs`),
	 * ES 매핑(`place_search.json`·`place_suggest.json`)의 분석기·필드 타입이 바뀔 때.
	 * 값을 올리면 질의기는 **재색인 전까지
	 * 뜨지 않는다** — 그게 의도다. 옛 스키마 위에서 새 질의를 돌리면 조용히 0건이 된다.
	 */
	const val SCHEMA_VERSION = 1

	/** 도장을 보관하는 ES 인덱스. 문서 id 는 파이프라인 이름. */
	const val ES_INDEX = "psp_index_meta"

	const val PIPELINE_SEARCH = "search"
	const val PIPELINE_SUGGEST = "suggest"
	const val PIPELINE_VECTOR = "vector"

	/**
	 * 한 파이프라인의 도장. ES 문서로 그대로 오가므로 필드명은 snake_case 다
	 * (`CheckpointStore.Checkpoint` 와 같은 규칙).
	 *
	 * 임베딩 필드는 벡터 파이프라인에서만 채운다 — 키워드 인덱스는 모델과 무관하다.
	 */
	data class Stamp(
		val schema_version: Int = 0,
		val embedding_model: String? = null,
		val embedding_dim: Int? = null,
	)

	/**
	 * 지금 이 프로세스가 **실제로 쓰고 있는** 값으로 도장을 만든다.
	 *
	 * 모델 이름·차원을 설정값이 아니라 로드된 모델에서 읽는 게 핵심이다. 설정은 "그럴 것이다"이고
	 * 로드된 모델은 "그렇다"라서, 설정만 맞고 실제 파일이 다른 경우까지 잡힌다.
	 */
	fun stamp(embeddingModel: String? = null, embeddingDim: Int? = null) =
		Stamp(SCHEMA_VERSION, embeddingModel, embeddingDim)

	/** 대조 결과. */
	sealed interface Verdict {
		/** 도장이 우리 것과 같다. */
		data object Ok : Verdict

		/**
		 * 도장이 아예 없다. 분리 이전에 만들어진 인덱스이거나 아직 재색인 전이다.
		 * 재색인을 강제하지 않기 위해 **경고만** 한다 — 여기서 막으면 옛 인덱스로는 아무도 못 뜬다.
		 */
		data object Missing : Verdict

		/** 도장이 다르다. 어긋난 항목을 사람이 읽을 수 있게 담는다. */
		data class Mismatch(val differences: List<String>) : Verdict
	}

	/**
	 * 색인 시점 도장([actual])과 지금 이 프로세스의 도장([expected])을 견준다.
	 *
	 * 도장에 없는(null) 임베딩 항목은 비교하지 않는다 — 키워드 파이프라인이거나, 벡터 기능을
	 * 끄고 뜬 질의기다. "모르는 것"을 "다르다"로 취급하면 정상 구성이 기동에 실패한다.
	 */
	fun verify(actual: Stamp?, expected: Stamp): Verdict {
		if (actual == null) return Verdict.Missing

		val differences = buildList {
			if (actual.schema_version != expected.schema_version) {
				add("문서 스키마 버전: 색인=${actual.schema_version}, 질의=${expected.schema_version}")
			}
			compare("임베딩 모델", actual.embedding_model, expected.embedding_model)?.let(::add)
			compare("임베딩 차원", actual.embedding_dim, expected.embedding_dim)?.let(::add)
		}
		return if (differences.isEmpty()) Verdict.Ok else Verdict.Mismatch(differences)
	}

	private fun compare(label: String, actual: Any?, expected: Any?): String? =
		if (actual != null && expected != null && actual != expected) {
			"$label: 색인=$actual, 질의=$expected"
		} else {
			null
		}
}
