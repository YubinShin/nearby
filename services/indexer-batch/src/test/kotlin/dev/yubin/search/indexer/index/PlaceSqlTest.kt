package dev.yubin.search.indexer.index

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceSqlTest {
	@Test
	fun `both queries carry the duplicate verdict so the writer can act on it`() {
		listOf(PlaceSql.SELECT_ALL, PlaceSql.SELECT_SINCE).forEach { sql ->
			assertTrue("left join public.place_duplicate d using (place_id)" in sql, sql)
			assertTrue("d.survivor_id" in sql, sql)
		}
	}

	@Test
	fun `a full rebuild never reads duplicates - the fresh index has nothing to delete`() {
		assertTrue("d.place_id is null" in PlaceSql.SELECT_ALL)
		assertTrue("p.deleted_at is null" in PlaceSql.SELECT_ALL)
	}

	@Test
	fun `an incremental run does read duplicates so it can delete what is already indexed`() {
		assertFalse("d.place_id is null" in PlaceSql.SELECT_SINCE)
		assertTrue("p.updated_at > ?" in PlaceSql.SELECT_SINCE)
	}
}
