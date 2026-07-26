package dev.yubin.search.core.embed

import dev.yubin.search.core.brand.Brands
import dev.yubin.search.core.place.PlaceRow

object PlaceVectorText {
	fun of(r: PlaceRow): String {
		val brand = Brands.resolve(r.brand, r.name, r.branch)
		val name = listOfNotNull(Brands.embedText(brand, r.name), r.branch).joinToString(" ")
		val category = listOfNotNull(r.categoryMid, r.categorySmall).distinct().joinToString(" ")
		val region = listOfNotNull(r.sigungu, r.dong).joinToString(" ")
		return listOf(name, category, region).filter { it.isNotBlank() }.joinToString(". ")
	}
}
