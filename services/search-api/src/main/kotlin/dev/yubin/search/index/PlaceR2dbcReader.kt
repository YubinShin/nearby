package dev.yubin.search.index

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * PostGIS 원천 창고를 **스트림으로** 읽는다. 64k+ 행을 한꺼번에 메모리에 올리지 않고
 * 흘려보내며 색인할 수 있도록 Flow 로 돌려준다.
 */
@Repository
class PlaceR2dbcReader(private val client: DatabaseClient) {

	/** 전체 재색인용 — 살아있는(삭제 안 된) 행만 읽는다. 전체 재색인이 곧 tombstone 청소. */
	fun readAll(): Flow<PlaceRow> = query(client.sql(SELECT_ALL))

	/** 증분 색인용 — checkpoint '이후'에 바뀐 행만. 삭제된 행도 포함(그래야 ES에서 지울 수 있음). */
	fun readSince(since: OffsetDateTime): Flow<PlaceRow> =
		query(client.sql(SELECT_SINCE).bind("since", since))

	/**
	 * 원천에서 가장 최근에 바뀐 시각. 색인 lag 지표의 기준점이다.
	 * `place_updated_at_idx` 덕에 인덱스 끝만 읽으면 되는 값싼 질의다.
	 */
	suspend fun maxUpdatedAt(): OffsetDateTime? =
		// max() 대신 정렬+limit 1 — 인덱스 끝을 바로 집고, 빈 테이블이면 'null 값을 담은 한 행'이
		// 아니라 아예 0행이 와서 널 처리가 단순해진다.
		client.sql("select updated_at as ts from public.place order by updated_at desc limit 1")
			.map { row, _ -> row.get("ts", OffsetDateTime::class.java)!! }
			.one()
			.awaitSingleOrNull()

	private fun query(spec: org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec): Flow<PlaceRow> =
		spec
			.map { row, _ ->
				PlaceRow(
					placeId = row.get("place_id", String::class.java)!!,
					name = row.get("name", String::class.java)!!,
					branch = row.get("branch", String::class.java),
					categoryLarge = row.get("category_large", String::class.java),
					categoryMid = row.get("category_mid", String::class.java),
					categorySmall = row.get("category_small", String::class.java),
					sido = row.get("sido", String::class.java),
					sigungu = row.get("sigungu", String::class.java),
					dong = row.get("dong", String::class.java),
					jibunAddress = row.get("jibun_address", String::class.java),
					roadAddress = row.get("road_address", String::class.java),
					lon = row.get("lon", Number::class.java)?.toDouble(),
					lat = row.get("lat", Number::class.java)?.toDouble(),
					updatedAt = row.get("updated_at", OffsetDateTime::class.java)!!,
					deletedAt = row.get("deleted_at", OffsetDateTime::class.java),
				)
			}
			.all()
			.asFlow()

	companion object {
		private val SELECT_BASE = """
			select place_id, name, branch,
			       category_large, category_mid, category_small,
			       sido, sigungu, dong, jibun_address, road_address,
			       lon, lat, updated_at, deleted_at
			from public.place
		""".trimIndent()
		private val SELECT_ALL = "$SELECT_BASE\nwhere deleted_at is null"

		// checkpoint(ES date)는 밀리초 정밀도라, PostGIS 마이크로초와 비교하면 경계값이 매번 재매칭된다.
		// 양쪽을 밀리초로 잘라 비교해 정밀도 불일치를 없앤다. (운영에선 단조 증가 version 컬럼이 더 안전)
		private val SELECT_SINCE = "$SELECT_BASE\nwhere date_trunc('milliseconds', updated_at) > :since"
	}
}
