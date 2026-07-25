package dev.yubin.search.indexer.index

import dev.yubin.search.core.place.PlaceRow
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * PostGIS 원천 창고에서 장소를 읽는 **SQL 과 매핑**. (ADR 0013)
 *
 * ### 왜 여기엔 '읽는 루프'가 없나
 * 전에는 `PlaceR2dbcReader` 가 `Flow<PlaceRow>` 를 돌려주고, 서비스가 그 흐름을 모아서 배치로
 * 잘라 쓰는 루프를 직접 갖고 있었다. 지금은 그 루프가 **Spring Batch 의 chunk** 다 —
 * 읽기는 `JdbcCursorItemReader`(프레임워크), 잘라 쓰기는 chunk 크기 설정이 맡는다.
 *
 * 그래서 이 파일에 남는 건 프레임워크가 알 수 없는 것뿐이다: **어떤 SQL 을 던지고, 한 행을
 * 어떻게 [PlaceRow] 로 옮기는가.** 리더 조립은 `KeywordIndexJobConfig`·`VectorIndexJobConfig` 에서 한다.
 *
 * ### 커서로 읽는다 — 64k 행을 메모리에 올리지 않는다
 * `JdbcCursorItemReader` 는 커넥션 하나를 스텝 내내 붙잡고 `ResultSet` 을 커서로 훑는다.
 * 단, postgres JDBC 드라이버는 **autoCommit=false 이고 fetchSize 가 설정돼 있을 때만** 진짜
 * 커서를 쓴다. 둘 중 하나만 빠지면 드라이버가 결과 전체를 조용히 메모리에 올린다 — 그래서
 * 리더를 조립하는 쪽에서 `connectionAutoCommit(false)` 와 `fetchSize` 를 **둘 다** 준다.
 */
object PlaceSql {

	// place_brand 는 **우리가 만든 파생 테이블**이라 left join 이다 — 없는 게 정상이다.
	// 주의: 브랜드만 바뀌면 place.updated_at 이 안 움직여 증분 색인이 못 잡는다.
	//       지금은 전체 재색인으로만 반영된다 (셀프 크리틱 #20).
	private val SELECT_BASE = """
		select p.place_id, p.name, p.branch, b.brand,
		       p.category_large, p.category_mid, p.category_small,
		       p.sido, p.sigungu, p.dong, p.jibun_address, p.road_address,
		       p.lon, p.lat, p.updated_at, p.deleted_at
		from public.place p
		left join public.place_brand b using (place_id)
	""".trimIndent()

	/** 전체 재색인용 — 살아있는(삭제 안 된) 행만 읽는다. 전체 재색인이 곧 tombstone 청소. */
	val SELECT_ALL = "$SELECT_BASE\nwhere p.deleted_at is null\norder by p.place_id"

	/**
	 * 증분 색인용 — checkpoint '이후'에 바뀐 행만. 삭제된 행도 포함(그래야 ES에서 지울 수 있음).
	 *
	 * checkpoint(ES date)는 밀리초 정밀도라, PostGIS 마이크로초와 비교하면 경계값이 매번 재매칭된다.
	 * 양쪽을 밀리초로 잘라 비교해 정밀도 불일치를 없앤다. (운영에선 단조 증가 version 컬럼이 더 안전)
	 *
	 * R2DBC 의 이름 있는 바인딩(`:since`)과 달리 JDBC 는 `?` 위치 바인딩이다 — 인자는 하나뿐이다.
	 */
	val SELECT_SINCE = "$SELECT_BASE\nwhere date_trunc('milliseconds', p.updated_at) > ?\norder by p.place_id"

	/**
	 * 원천에서 가장 최근에 바뀐 시각. 색인 lag 지표의 기준점이다.
	 * `place_updated_at_idx` 덕에 인덱스 끝만 읽으면 되는 값싼 질의다.
	 *
	 * max() 대신 정렬+limit 1 — 인덱스 끝을 바로 집고, 빈 테이블이면 'null 값을 담은 한 행'이
	 * 아니라 아예 0행이 와서 널 처리가 단순해진다.
	 */
	const val SELECT_MAX_UPDATED_AT = "select updated_at from public.place order by updated_at desc limit 1"
}

/**
 * `ResultSet` 한 행 → [PlaceRow].
 *
 * 널 가능한 숫자 컬럼(lon/lat)은 `getDouble` 이 널을 **0.0 으로 뭉개기** 때문에 `wasNull()` 로
 * 되돌려야 한다. 좌표가 없는 장소를 (0,0) — 기니 만 앞바다 — 로 색인하면 반경 검색이 조용히
 * 틀린다. R2DBC 에선 `Number?` 로 받아 자연스럽게 널이 보존됐던 자리다.
 */
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
	)

	private fun ResultSet.nullableDouble(column: String): Double? =
		getDouble(column).takeUnless { wasNull() }
}

/**
 * 색인 루프 밖에서 원천에 던지는 **단발 질의**들. (스트리밍 읽기는 ItemReader 가 맡는다)
 *
 * 블로킹이다 — 부르는 쪽이 lag 지표 스케줄러(전용 스레드)와 job 스레드뿐이라 기다려도 된다.
 */
@Repository
class PlaceSourceDao(private val jdbc: JdbcClient) {

	/** 원천 최신 변경 시각. 비어있으면 null. */
	fun maxUpdatedAt(): OffsetDateTime? =
		jdbc.sql(PlaceSql.SELECT_MAX_UPDATED_AT)
			// 컬럼 하나뿐이라 인덱스로 집는다. 타입 변환을 드라이버에 맡기지 않고 못박아,
			// timestamptz → OffsetDateTime 이 확실히 오프셋을 갖고 오게 한다.
			.query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java) }
			.optional()
			.orElse(null)
}
