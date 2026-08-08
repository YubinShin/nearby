package dev.yubin.search.core.brand

import dev.yubin.search.core.analysis.Digest

object Brands {
	val aliases: Map<String, List<String>> = load()

	private val byLength: List<Pair<String, String>> =
		aliases.entries
			.flatMap { (canonical, forms) -> forms.map { normalize(it) to canonical } }
			.sortedByDescending { it.first.length }

	val fingerprint: String = Digest.of(byLength.map { (form, canonical) -> "$form=$canonical" })

	fun resolve(recovered: String?, name: String, branch: String? = null): String? =
		recovered?.takeIf { it.isNotBlank() } ?: canonical(name, branch)

	fun canonical(name: String, branch: String? = null): String? {
		val text = normalize(name + (branch ?: ""))
		if (text.isEmpty()) return null
		return byLength.firstOrNull { (form, _) -> startsWithBrand(text, form) }?.second
	}

	private fun startsWithBrand(text: String, form: String): Boolean {
		if (!text.startsWith(form)) return false
		val next = text.getOrNull(form.length) ?: return true
		return !(form.last().isLatinLetter() && next.isLatinLetter())
	}

	private fun Char.isLatinLetter() = this in 'a'..'z'

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
			?: error("brand seed not found: $RESOURCE")
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
