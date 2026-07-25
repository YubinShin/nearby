package dev.yubin.search.indexer.batch

/**
 * job·step 이름과 `ExecutionContext` 키를 한곳에 모은다. (ADR 0013)
 *
 * 이 문자열들은 **DB 에 남는다** — `BATCH_JOB_INSTANCE.JOB_NAME`, `BATCH_STEP_EXECUTION.STEP_NAME`
 * 으로 저장되고, job 이름은 재시작·이력 조회의 키다. 그래서 리팩터로 무심코 바꾸면 과거 이력과
 * 연결이 끊긴다. 흩어진 리터럴로 두지 않고 여기서 못박는 이유다.
 */
object IndexJobs {

	// ---- job 이름 (HTTP 로 노출되는 이름이기도 하다) ----

	const val KEYWORD_REBUILD = "keywordRebuild"
	const val KEYWORD_INCREMENTAL = "keywordIncremental"
	const val VECTOR_REBUILD = "vectorRebuild"
	const val VECTOR_INCREMENTAL = "vectorIncremental"

	/**
	 * 실행마다 새 값을 넣는 **식별 파라미터**. 이게 있어야 같은 job 을 여러 번 돌릴 수 있다.
	 *
	 * Spring Batch 는 `(job 이름, 식별 파라미터)` 가 같으면 **같은 JobInstance** 로 본다. 파라미터가
	 * 없으면 두 번째 실행이 "이미 완료된 인스턴스"로 거절된다(`JobInstanceAlreadyCompleteException`).
	 * 재색인은 같은 입력으로 몇 번이든 다시 돌릴 수 있어야 하니, 실행 시각을 넣어 매번 새
	 * 인스턴스가 되게 한다.
	 *
	 * **즉 우리는 실패한 job 을 '재시작'하지 않고 '다시 실행'한다.** 전체 재색인에서는 그게 맞다 —
	 * 재시작은 몇 시간 전에 열어둔 커서를 이어받는 셈이라, 그때의 원천과 지금의 원천이 섞인
	 * 인덱스가 만들어진다. 전체 재색인이 원하는 건 **한 시점의 일관된 스냅샷**이다.
	 * 증분은 애초에 watermark 로 이어받으므로 다시 실행하는 것이 곧 이어받는 것이다.
	 */
	const val PARAM_REQUESTED_AT = "requestedAt"

	/** 스케줄러가 넣는 표시. 이력에서 "이건 사람이 누른 게 아니다"를 구분하려고 남긴다. */
	const val PARAM_TRIGGER = "trigger"
	const val TRIGGER_MANUAL = "manual"
	const val TRIGGER_SCHEDULE = "schedule"

	// ---- step 이름 ----

	const val STEP_PREPARE = "prepare"
	const val STEP_PROMOTE = "promote"

	/**
	 * 적재 step 은 전체·증분 두 job 이 **공유한다** — 차이는 prepare 가 컨텍스트에 넣어준 값뿐이다.
	 * 그래서 job 이름으로 접두어를 붙일 수 없고, 파이프라인 이름으로 구분한다.
	 * (키워드와 벡터가 같은 `load` 라는 이름을 쓰면 `BATCH_STEP_EXECUTION` 을 읽을 때 헷갈린다.)
	 */
	const val STEP_KEYWORD_LOAD = "keywordLoad"
	const val STEP_VECTOR_LOAD = "vectorLoad"

	/**
	 * `ExecutionContext` 키.
	 *
	 * step 이 계산한 값을 다음 step 에게 넘기는 통로다. 전에는 한 함수 안의 지역 변수였지만,
	 * step 으로 쪼개면 서로 다른 트랜잭션·다른 시점에 돌기 때문에 프레임워크가 관리하는 이
	 * 저장소를 거쳐야 한다. 대신 **DB 에 남아서** 실패한 실행이 어디까지 갔는지 나중에 볼 수 있다.
	 *
	 * 값은 String/Long 만 쓴다 — 이 맵은 직렬화되어 `BATCH_*_EXECUTION_CONTEXT` 에 저장되므로,
	 * 도메인 객체를 그대로 넣으면 나중에 클래스가 바뀔 때 옛 이력을 못 읽는다.
	 */
	object Ctx {
		/** 이번 실행이 만든 새 버전 인덱스/컬렉션 이름. prepare → load·promote. */
		const val SEARCH_INDEX = "searchIndex"
		const val SUGGEST_INDEX = "suggestIndex"
		const val COLLECTION = "collection"

		/** 증분이 어디서부터 읽을지 (ISO-8601, 없으면 키 자체가 없다). prepare → load. */
		const val SINCE = "since"

		/** load 가 실제로 처리한 것들. load → promote·HTTP 응답. */
		const val MAX_UPDATED_AT = "maxUpdatedAt"
		const val READ = "read"
		const val UPSERTED = "upserted"
		const val DELETED = "deleted"
		const val EMBED_MS = "embedMs"

		/** promote 가 정리한 옛 버전들 (쉼표로 이음). */
		const val REMOVED = "removed"

		/**
		 * **alias 스왑이 끝났다** = 여기 적힌 인덱스/컬렉션은 이제 서빙 중이다.
		 *
		 * promote step 이 스왑 직후에 넣는다. 이 뒤로 job 이 실패하더라도
		 * [OrphanIndexCleanupListener] 는 대상을 지우면 안 된다 — 지우는 순간 검색 전면 장애다.
		 * "만들다 만 인덱스"와 "이미 승격된 인덱스"를 같은 컨텍스트 키로 구분할 수 없어서 따로 둔다.
		 */
		const val PROMOTED = "promoted"

		/** 전진시킨 watermark. */
		const val CHECKPOINT = "checkpoint"
	}
}
