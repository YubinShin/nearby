package dev.yubin.search.core.analysis

import java.security.MessageDigest

object Digest {
	fun of(terms: List<String>): String =
		MessageDigest.getInstance("SHA-256")
			.digest(terms.joinToString("|").toByteArray(Charsets.UTF_8))
			.take(BYTES)
			.joinToString("") { "%02x".format(it.toInt() and 0xff) }

	private const val BYTES = 6
}
