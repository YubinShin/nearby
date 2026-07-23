package dev.yubin.search.query

import co.elastic.clients.elasticsearch._types.GeoLocation
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode
import co.elastic.clients.elasticsearch._types.query_dsl.Operator
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType

/**
 * 검색 질의를 **순수 함수로** 만든다. ES 클라이언트도, 코루틴도, 스프링도 여기엔 없다.
 *
 * 랭킹은 이 프로젝트의 알맹이라 서비스 코드에 섞지 않고 한 곳에 모았다.
 * 그래야 "왜 이 순서인가"를 이 파일 하나로 설명할 수 있고, 단위 테스트로 고정할 수 있다.
 */
object PlaceQueries {

	/**
	 * 본문 검색 필드와 가중치.
	 *
	 * 전부 komoran 으로 분석되는 필드라 `cross_fields` 가 하나의 그룹으로 묶어 처리한다
	 * (분석기가 다르면 그룹이 쪼개져 의도한 "단어를 필드들에 흩어 매칭" 이 깨진다).
	 * 카테고리·행정동은 keyword 원본을 필터용으로 두고, 매칭은 `.txt` 멀티필드로 한다.
	 */
	val SEARCH_FIELDS = listOf(
		"name^5",             // 상호명이 가장 강한 신호
		// 상호명에서 빠진 브랜드를 복원한 필드 (place_brand). 상호명과 같은 무게를 준다 —
		// 사용자가 '스타벅스'를 칠 때 기대하는 건 상호명을 친 것과 똑같기 때문이다.
		"brand^5",
		"branch^3",           // "역삼점" 같은 지점명
		"category_small.txt^2",
		"category_mid.txt",
		"dong.txt^1.5",       // "역삼동"
		"sigungu.txt",
		"road_address",
		"jibun_address",
	)

	/**
	 * 본문 검색.
	 *
	 * - `cross_fields` + `AND`: "역삼 커피" 는 *역삼* 과 *커피* 가 **필드들에 흩어져서라도 모두** 있어야 한다.
	 *   (역삼=주소/행정동, 커피=상호/카테고리) 한 단어만 걸린 문서를 위로 올리지 않으려는 정밀도 우선 선택.
	 * - 여기에 상호명 구절 일치를 `should` 로 얹어, 같은 조건이면 이름이 통째로 맞는 곳을 위로.
	 *
	 * @param relaxed true 면 모든 단어 요구를 풀어 절반 이상만 맞아도 걸리게 한다 (0건 폴백용).
	 */
	fun search(req: SearchRequest, relaxed: Boolean = false): Query = Query.of { q ->
		q.bool { b ->
			b.must { m ->
				m.multiMatch { mm ->
					mm.query(req.q)
						.fields(SEARCH_FIELDS)
						.type(TextQueryType.CrossFields)
					// 70%: 단어 2개면 1개만 맞아도(=완화), 3개면 2개는 맞아야 한다(ES 는 내림 처리).
					// 50% 로 두면 3단어 질의에서 1단어만 맞은 문서까지 끌려와 폴백이 너무 시끄러워진다(실측 6,876건).
					if (relaxed) mm.operator(Operator.Or).minimumShouldMatch("70%")
					else mm.operator(Operator.And)
				}
			}
			b.should { s -> s.matchPhrase { mp -> mp.field("name").query(req.q).boost(3.0f) } }
			filters(req).forEach { f -> b.filter(f) }
			b
		}
	}

	/**
	 * 자동완성.
	 *
	 * 색인은 edge_ngram(스→스타→스타벅→스타벅스), 질의는 표준 분석기 — 그래서 한 글자만 쳐도 걸린다.
	 * 문제는 순서였다: 순수 `match` 면 BM25 가 골라주는 대로라 "스타" 에 엉뚱한 가게가 먼저 나온다(크리틱 #10).
	 * 두 가지 신호를 얹어 고쳤다.
	 *  1. **접두 일치 가산점** — 이름이 실제로 그 글자로 *시작*하면 강하게 올린다.
	 *  2. **이름 길이 역수** — 짧은 이름일수록 대표 상호일 확률이 높다("스타벅스" > "스타벅스 역삼점").
	 *
	 * 인기도(클릭·주문) 신호가 있으면 그게 정답이지만 지금 원천에 없다 → 길이를 대리 신호로 쓴다.
	 */
	fun suggest(req: SuggestRequest): Query = Query.of { root ->
		root.functionScore { fs ->
			fs.query { q ->
				q.bool { b ->
					// `name` 이 아니라 `label`(브랜드+상호명)로 맞춘다 — 상호명에서 브랜드가 빠져
					// 있던 가게도 '스타'로 걸려야 하고, 걸린 뒤 보여줄 글자도 이쪽이다.
					b.must { m -> m.match { mt -> mt.field("label").query(req.q).operator(Operator.And) } }
					b.should { s -> s.prefix { p -> p.field("label.raw").value(req.q.lowercase()).boost(3.0f) } }
				}
			}
			fs.functions { f ->
				f.fieldValueFactor { fvf ->
					fvf.field("name_length")
						.modifier(FieldValueFactorModifier.Reciprocal)
						.factor(1.0)
						.missing(10.0)
				}
			}
			fs.boostMode(FunctionBoostMode.Multiply)
		}
	}

	/**
	 * 필터 절 — 점수에 관여하지 않는 순수 조건. `filter` 문맥이라 ES 가 캐시할 수 있다.
	 * 정확 일치는 분석되지 않은 keyword 원본을 쓴다(`.txt` 아님).
	 */
	fun filters(req: SearchRequest): List<Query> = buildList {
		req.sigungu?.let { add(term("sigungu", it)) }
		req.dong?.let { add(term("dong", it)) }
		req.categoryLarge?.let { add(term("category_large", it)) }
		if (req.hasGeo && req.radiusM != null) add(withinRadius(req.lat!!, req.lon!!, req.radiusM))
	}

	private fun term(field: String, value: String): Query =
		Query.of { q -> q.term { t -> t.field(field).value(value) } }

	private fun withinRadius(lat: Double, lon: Double, radiusM: Int): Query =
		Query.of { q ->
			q.geoDistance { g ->
				g.field("location")
					.distance("${radiusM}m")
					.location(geoPoint(lat, lon))
			}
		}

	fun geoPoint(lat: Double, lon: Double): GeoLocation =
		GeoLocation.of { g -> g.latlon { ll -> ll.lat(lat).lon(lon) } }
}
