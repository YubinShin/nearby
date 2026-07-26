package dev.yubin.search.indexer.index

import co.elastic.clients.elasticsearch.ElasticsearchClient
import dev.yubin.search.core.index.IndexVersion
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Service
class IndexAdminService(private val es: ElasticsearchClient) {
	fun createNextVersion(alias: String, mappingResource: String): String {
		val name = IndexVersion.newName(alias)
		ClassPathResource(mappingResource).inputStream.use { json ->
			es.indices().create { it.index(name).withJson(json) }
		}
		return name
	}

	fun indicesBehind(alias: String): Set<String> =
		try {
			es.indices().getAlias { it.name(alias) }.aliases().keys.toSet()
		} catch (_: Exception) {
			emptySet()
		}

	fun swapAlias(alias: String, newIndex: String): Set<String> {
		val previous = indicesBehind(alias)
		es.indices().updateAliases { u ->
			u.actions { a -> a.remove { r -> r.index("*").alias(alias).mustExist(false) } }
				.actions { a -> a.add { ad -> ad.index(newIndex).alias(alias) } }
		}
		return previous - newIndex
	}

	fun deleteIndices(indices: Set<String>) {
		if (indices.isNotEmpty()) es.indices().delete { it.index(indices.toList()) }
	}

	fun sweepOrphansAbove(alias: String): Set<String> {
		val current = indicesBehind(alias).mapNotNull { IndexVersion.tokenOf(alias, it) }.maxOrNull()
			?: return emptySet()

		val above = versionsOf(alias).filter { it.second > current }.map { it.first }.toSet()
		deleteIndices(above)
		return above
	}

	fun reconcile(alias: String, keep: Int): Set<String> {
		val current = indicesBehind(alias).mapNotNull { IndexVersion.tokenOf(alias, it) }.maxOrNull()
			?: return emptySet()

		val below = versionsOf(alias)
			.filter { it.second < current }
			.sortedByDescending { it.second }

		val doomed = below.drop((keep - 1).coerceAtLeast(0)).map { it.first }.toSet()
		deleteIndices(doomed)
		return doomed
	}

	private fun versionsOf(alias: String): List<Pair<String, String>> =
		es.indices()
			.get { it.index("${alias}_*").ignoreUnavailable(true) }
			.indices().keys
			.mapNotNull { name -> IndexVersion.tokenOf(alias, name)?.let { name to it } }

	fun maxUpdatedAt(index: String, field: String = "updated_at"): OffsetDateTime? {
		val resp = es.search(
			{ s -> s.index(index).size(0).aggregations("max_ts") { a -> a.max { m -> m.field(field) } } },
			Void::class.java,
		)
		val millis = resp.aggregations()["max_ts"]?.max()?.value()
		return if (millis == null || millis.isNaN() || millis <= 0.0) null
		else Instant.ofEpochMilli(millis.toLong()).atOffset(ZoneOffset.UTC)
	}
}
