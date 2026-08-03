package dev.yubin.search.indexer.observability

import dev.yubin.search.indexer.index.CheckpointStore
import dev.yubin.search.indexer.index.PlaceSourceDao
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndexLagMetricsTest {
	private val registry = SimpleMeterRegistry()
	private val checkpoints = mock(CheckpointStore::class.java)
	private val source = mock(PlaceSourceDao::class.java)

	private fun at(hour: Int) = OffsetDateTime.of(2026, 8, 3, hour, 0, 0, 0, ZoneOffset.UTC)

	private fun lagOf(pipeline: String): Double? =
		registry.find("psp.index.lag.seconds").tag("pipeline", pipeline).gauge()?.value()

	private fun metrics(vectorEnabled: Boolean = true) =
		IndexLagMetrics(registry, checkpoints, source, vectorEnabled)

	@Test
	fun `each pipeline reports its own lag`() {
		doReturn(at(12)).`when`(source).maxUpdatedAt()
		doReturn(at(12)).`when`(checkpoints).get(CheckpointStore.PLACE_PIPELINE)
		doReturn(at(11)).`when`(checkpoints).get(CheckpointStore.PLACE_VECTOR)

		metrics().refresh()

		assertEquals(0.0, lagOf("keyword"))
		assertEquals(3600.0, lagOf("vector"))
	}

	@Test
	fun `a vector pipeline that fell behind is visible even when keyword caught up`() {
		doReturn(at(12)).`when`(source).maxUpdatedAt()
		doReturn(at(12)).`when`(checkpoints).get(CheckpointStore.PLACE_PIPELINE)
		doReturn(at(1)).`when`(checkpoints).get(CheckpointStore.PLACE_VECTOR)

		metrics().refresh()

		assertEquals(0.0, lagOf("keyword"))
		assertEquals(39600.0, lagOf("vector"))
	}

	@Test
	fun `a missing checkpoint reports minus one rather than zero`() {
		doReturn(at(12)).`when`(source).maxUpdatedAt()
		doReturn(null).`when`(checkpoints).get(CheckpointStore.PLACE_PIPELINE)
		doReturn(at(12)).`when`(checkpoints).get(CheckpointStore.PLACE_VECTOR)

		metrics().refresh()

		assertEquals(-1.0, lagOf("keyword"))
		assertEquals(0.0, lagOf("vector"))
	}

	@Test
	fun `no vector gauge is registered when vectors are off`() {
		metrics(vectorEnabled = false)

		assertNull(lagOf("vector"))
	}
}