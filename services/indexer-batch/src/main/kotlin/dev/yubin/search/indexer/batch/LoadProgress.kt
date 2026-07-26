package dev.yubin.search.indexer.batch

import dev.yubin.search.core.place.PlaceRow
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.ExecutionContext
import java.time.OffsetDateTime

class LoadProgress(private val ctx: ExecutionContext) {
	var read: Long
		get() = ctx.getLong(IndexJobs.Ctx.READ, 0L)
		private set(v) = ctx.putLong(IndexJobs.Ctx.READ, v)

	var upserted: Long
		get() = ctx.getLong(IndexJobs.Ctx.UPSERTED, 0L)
		private set(v) = ctx.putLong(IndexJobs.Ctx.UPSERTED, v)

	var deleted: Long
		get() = ctx.getLong(IndexJobs.Ctx.DELETED, 0L)
		private set(v) = ctx.putLong(IndexJobs.Ctx.DELETED, v)

	var embedMillis: Long
		get() = ctx.getLong(IndexJobs.Ctx.EMBED_MS, 0L)
		set(v) = ctx.putLong(IndexJobs.Ctx.EMBED_MS, v)

	var maxUpdatedAt: OffsetDateTime?
		get() = ctx.getString(IndexJobs.Ctx.MAX_UPDATED_AT, "").ifEmpty { null }?.let(OffsetDateTime::parse)
		private set(v) = ctx.putString(IndexJobs.Ctx.MAX_UPDATED_AT, v?.toString() ?: "")

	fun record(rows: List<PlaceRow>, upserted: Int, deleted: Int) {
		read += rows.size
		this.upserted += upserted
		this.deleted += deleted

		val batchMax = rows.maxOfOrNull { it.updatedAt } ?: return
		val current = maxUpdatedAt
		if (current == null || batchMax.isAfter(current)) maxUpdatedAt = batchMax
	}

	fun promoteTo(stepExecution: StepExecution) {
		val job = stepExecution.jobExecution.executionContext
		job.putLong(IndexJobs.Ctx.READ, read)
		job.putLong(IndexJobs.Ctx.UPSERTED, upserted)
		job.putLong(IndexJobs.Ctx.DELETED, deleted)
		job.putLong(IndexJobs.Ctx.EMBED_MS, embedMillis)
		maxUpdatedAt?.let { job.putString(IndexJobs.Ctx.MAX_UPDATED_AT, it.toString()) }
	}

	companion object {
		fun of(stepExecution: StepExecution) = LoadProgress(stepExecution.executionContext)

		fun ofJob(stepExecution: StepExecution) = LoadProgress(stepExecution.jobExecution.executionContext)
	}
}
