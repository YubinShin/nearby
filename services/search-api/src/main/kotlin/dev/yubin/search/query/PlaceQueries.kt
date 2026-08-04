package dev.yubin.search.query

import co.elastic.clients.elasticsearch._types.GeoLocation
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode
import co.elastic.clients.elasticsearch._types.query_dsl.Operator
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType

object PlaceQueries {
	val SEARCH_FIELDS = listOf(
		"name^5",
		"brand_text^5",
		"branch^3",
		"category_small.txt^2",
		"category_mid.txt",
		"dong.txt^1.5",
		"sigungu.txt",
		"road_address",
		"jibun_address",
	)

	fun search(req: SearchRequest, relaxed: Boolean = false): Query = Query.of { q ->
		q.bool { b ->
			b.must { m ->
				m.multiMatch { mm ->
					mm.query(req.q)
						.fields(SEARCH_FIELDS)
						.type(TextQueryType.CrossFields)
					if (relaxed) mm.operator(Operator.Or).minimumShouldMatch("70%")
					else mm.operator(Operator.And)
				}
			}
			b.should { s -> s.matchPhrase { mp -> mp.field("name").query(req.q).boost(3.0f) } }
			filters(req).forEach { f -> b.filter(f) }
			b
		}
	}

	fun suggest(req: SuggestRequest): Query = Query.of { root ->
		root.functionScore { fs ->
			fs.query { q ->
				q.bool { b ->
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
