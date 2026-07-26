package dev.yubin.search.core.index

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object IndexVersion {
	private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

	fun newName(alias: String): String = "${alias}_${FORMAT.format(Instant.now())}"

	fun tokenOf(alias: String, name: String): String? =
		Regex("^${Regex.escape(alias)}_(\\d{14})$").find(name)?.groupValues?.get(1)
}
