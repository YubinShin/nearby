package dev.yubin.search.indexer.index

import dev.yubin.search.core.place.PlaceRow
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

object PlaceSql {
	private val SELECT_BASE = """
		select p.place_id, p.name, p.branch, b.brand,
		       p.category_large, p.category_mid, p.category_small,
		       p.sido, p.sigungu, p.dong, p.jibun_address, p.road_address,
		       p.lon, p.lat, p.updated_at, p.deleted_at, d.survivor_id
		from public.place p
		left join public.place_brand b using (place_id)
		left join public.place_duplicate d using (place_id)
	""".trimIndent()

	// 전체 재색인은 빈 인덱스에 쌓으므로 지울 것이 없다 — 소프트 삭제와 마찬가지로 아예 안 읽는다.
	val SELECT_ALL = "$SELECT_BASE\nwhere p.deleted_at is null and d.place_id is null\norder by p.place_id"

	// 증분은 살아 있는 인덱스에 덧쓴다. 중복으로 판정된 행도 읽어야 이미 들어간 문서를 지운다.
	val SELECT_SINCE = "$SELECT_BASE\nwhere p.updated_at > ?\norder by p.place_id"

	const val SELECT_MAX_UPDATED_AT = "select updated_at from public.place order by updated_at desc limit 1"

	const val SELECT_DB_NOW = "select clock_timestamp()"
}

object PlaceRowMapper : RowMapper<PlaceRow> {
	override fun mapRow(rs: ResultSet, rowNum: Int): PlaceRow = PlaceRow(
		placeId = rs.getString("place_id"),
		name = rs.getString("name"),
		branch = rs.getString("branch"),
		brand = rs.getString("brand"),
		categoryLarge = rs.getString("category_large"),
		categoryMid = rs.getString("category_mid"),
		categorySmall = rs.getString("category_small"),
		sido = rs.getString("sido"),
		sigungu = rs.getString("sigungu"),
		dong = rs.getString("dong"),
		jibunAddress = rs.getString("jibun_address"),
		roadAddress = rs.getString("road_address"),
		lon = rs.nullableDouble("lon"),
		lat = rs.nullableDouble("lat"),
		updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
		deletedAt = rs.getObject("deleted_at", OffsetDateTime::class.java),
		duplicateOf = rs.getString("survivor_id"),
	)

	private fun ResultSet.nullableDouble(column: String): Double? =
		getDouble(column).takeUnless { wasNull() }
}

@Repository
class PlaceSourceDao(private val jdbc: JdbcClient) {
	fun maxUpdatedAt(): OffsetDateTime? =
		jdbc.sql(PlaceSql.SELECT_MAX_UPDATED_AT)
			.query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java) }
			.optional()
			.orElse(null)

	fun dbNow(): OffsetDateTime =
		jdbc.sql(PlaceSql.SELECT_DB_NOW)
			.query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java) }
			.single()
}
