package dev.yubin.search.core.brand

object Brands {
	val aliases: Map<String, List<String>> = load()

	private val byLength: List<Pair<String, String>> =
		aliases.entries
			.flatMap { (canonical, forms) -> forms.map { normalize(it) to canonical } }
			.sortedByDescending { it.first.length }

	fun resolve(recovered: String?, name: String, branch: String? = null): String? =
		recovered?.takeIf { it.isNotBlank() } ?: canonical(name, branch)

	fun canonical(name: String, branch: String? = null): String? {
		val text = normalize(name + (branch ?: ""))
		if (text.isEmpty()) return null
		return byLength.firstOrNull { (form, _) -> text.startsWith(form) }?.second
	}

	fun searchText(canonical: String): String =
		aliases[canonical]?.joinToString(" ") ?: canonical

	fun display(brand: String?, name: String): String {
		if (brand.isNullOrBlank()) return name
		val normalized = normalize(name)
		val forms = (aliases[brand] ?: listOf(brand)).map(::normalize)
		return if (forms.any { normalized.startsWith(it) }) name else "$brand $name"
	}

	fun embedText(brand: String?, name: String): String {
		if (brand.isNullOrBlank()) return name
		return if (normalize(name).startsWith(normalize(brand))) name else "$brand $name"
	}

	private fun normalize(s: String) = s.replace(" ", "").lowercase()

	private fun load(): Map<String, List<String>> {
		val text = javaClass.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
			?: error("브랜드 시드가 없다: $RESOURCE")
		return text.lineSequence()
			.map { it.trim() }
			.filter { it.isNotEmpty() && !it.startsWith("#") }
			.map { line -> line.split("\t").map(String::trim).filter(String::isNotEmpty) }
			.filter { it.isNotEmpty() }

			.groupBy({ it.first() }, { it })
			.mapValues { (_, rows) -> rows.flatten().distinct() }
	}

	private const val RESOURCE = "/brands.tsv"
}
