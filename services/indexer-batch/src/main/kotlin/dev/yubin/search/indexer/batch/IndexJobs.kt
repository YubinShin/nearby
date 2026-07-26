package dev.yubin.search.indexer.batch

object IndexJobs {
	const val KEYWORD_REBUILD = "keywordRebuild"
	const val KEYWORD_INCREMENTAL = "keywordIncremental"
	const val VECTOR_REBUILD = "vectorRebuild"
	const val VECTOR_INCREMENTAL = "vectorIncremental"

	const val PARAM_REQUESTED_AT = "requestedAt"

	const val PARAM_TRIGGER = "trigger"
	const val TRIGGER_MANUAL = "manual"
	const val TRIGGER_SCHEDULE = "schedule"

	const val STEP_PREPARE = "prepare"
	const val STEP_PROMOTE = "promote"

	const val STEP_KEYWORD_LOAD = "keywordLoad"
	const val STEP_VECTOR_LOAD = "vectorLoad"

	object Ctx {
		const val SEARCH_INDEX = "searchIndex"
		const val SUGGEST_INDEX = "suggestIndex"
		const val COLLECTION = "collection"

		const val SINCE = "since"

		const val MAX_UPDATED_AT = "maxUpdatedAt"
		const val READ = "read"
		const val UPSERTED = "upserted"
		const val DELETED = "deleted"
		const val EMBED_MS = "embedMs"

		const val REMOVED = "removed"

		const val PROMOTED = "promoted"

		const val CHECKPOINT = "checkpoint"
	}
}
