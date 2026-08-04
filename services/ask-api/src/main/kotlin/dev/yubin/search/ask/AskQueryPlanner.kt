package dev.yubin.search.ask

object AskQueryPlanner {
	const val MAX_SIZE = 50
	const val DEFAULT_RADIUS_M = 2_000
	const val MAX_RADIUS_M = 50_000

	fun plan(
		raw: String,
		parsed: ParsedQuery?,
		defaultSize: Int,
		size: Int? = null,
		lat: Double? = null,
		lon: Double? = null,
		unsupported: List<String> = emptyList(),
	): SearchRequestPlan {
		val hasGeo = lat != null && lon != null
		val resolvedSize = (size ?: defaultSize).coerceIn(1, MAX_SIZE)

		if (parsed == null) {
			return SearchRequestPlan(
				q = raw.trim(),
				size = resolvedSize,
				lat = lat.takeIf { hasGeo },
				lon = lon.takeIf { hasGeo },
				radius = DEFAULT_RADIUS_M.takeIf { hasGeo },
				unsupported = unsupported,
			)
		}

		val q = compose(parsed).ifBlank { raw.trim() }

		return SearchRequestPlan(
			q = q,
			size = resolvedSize,
			lat = lat.takeIf { hasGeo },
			lon = lon.takeIf { hasGeo },
			radius = if (hasGeo) (parsed.radiusM ?: DEFAULT_RADIUS_M).coerceIn(1, MAX_RADIUS_M) else null,
			unmapped = unmapped(parsed, hasGeo),
			unsupported = unsupported,
		)
	}

	private fun compose(parsed: ParsedQuery): String {
		val seen = LinkedHashSet<String>()
		listOfNotNull(parsed.geoAnchor, parsed.keyword, parsed.categoryHint)
			.flatMap { it.trim().split(WHITESPACE) }
			.filter { it.isNotBlank() }
			.forEach { seen.add(it) }
		return seen.joinToString(" ")
	}

	private fun unmapped(parsed: ParsedQuery, hasGeo: Boolean): List<String> = buildList {
		if (!parsed.geoAnchor.isNullOrBlank()) add("geo_anchor")
		if (!parsed.categoryHint.isNullOrBlank()) add("category_hint")
		if (parsed.radiusM != null && !hasGeo) add("radius_m")
	}

	private val WHITESPACE = Regex("\\s+")
}
