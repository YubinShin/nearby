package dev.yubin.search.indexer.vector

import dev.yubin.search.core.index.IndexVersion
import dev.yubin.search.core.vector.QdrantContract
import dev.yubin.search.core.vector.VectorPoint
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.requiredBody
import java.time.Duration
import java.time.OffsetDateTime

@Component
class QdrantIndexStore(
	@Value("\${psp.qdrant.url}") baseUrl: String,
	@Value("\${psp.qdrant.connect-timeout-ms}") connectTimeoutMs: Long,
	@Value("\${psp.qdrant.write-timeout-ms}") writeTimeoutMs: Long,
) {
	private val http = RestClient.builder()
		.baseUrl(baseUrl)
		.requestFactory(
			SimpleClientHttpRequestFactory().apply {
				setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
				setReadTimeout(Duration.ofMillis(writeTimeoutMs))
			},
		)
		.build()

	fun createNextVersion(alias: String, dimension: Int): String {
		val name = IndexVersion.newName(alias)
		http.put().uri("/collections/{name}", name)
			.body(
				mapOf(
					"vectors" to mapOf("size" to dimension, "distance" to QdrantContract.DISTANCE),
					"hnsw_config" to mapOf(
						"m" to QdrantContract.HNSW_M,
						"ef_construct" to QdrantContract.HNSW_EF_CONSTRUCT,
					),
				),
			)
			.retrieve().toBodilessEntity()

		QdrantContract.PAYLOAD_INDEXES.forEach { (field, schema) ->
			http.put().uri("/collections/{name}/index?wait=true", name)
				.body(mapOf("field_name" to field, "field_schema" to schema))
				.retrieve().toBodilessEntity()
		}
		return name
	}

	fun collectionsBehind(alias: String): Set<String> =
		aliases().filter { it.alias_name == alias }.map { it.collection_name }.toSet()

	fun swapAlias(alias: String, newCollection: String): Set<String> {
		val previous = collectionsBehind(alias)
		val actions = previous.map { mapOf("delete_alias" to mapOf("alias_name" to alias)) } +
			listOf(mapOf("create_alias" to mapOf("collection_name" to newCollection, "alias_name" to alias)))
		http.post().uri("/collections/aliases")
			.body(mapOf("actions" to actions))
			.retrieve().toBodilessEntity()
		return previous - newCollection
	}

	fun deleteCollections(names: Set<String>) {
		names.forEach { http.delete().uri("/collections/{name}", it).retrieve().toBodilessEntity() }
	}

	fun sweepOrphansAbove(alias: String): Set<String> {
		val current = collectionsBehind(alias).mapNotNull { IndexVersion.tokenOf(alias, it) }.maxOrNull()
			?: return emptySet()

		val above = versionsOf(alias).filter { it.second > current }.map { it.first }.toSet()
		deleteCollections(above)
		return above
	}

	fun reconcile(alias: String, keep: Int): Set<String> {
		val current = collectionsBehind(alias).mapNotNull { IndexVersion.tokenOf(alias, it) }.maxOrNull()
			?: return emptySet()

		val below = versionsOf(alias)
			.filter { it.second < current }
			.sortedByDescending { it.second }

		val doomed = below.drop((keep - 1).coerceAtLeast(0)).map { it.first }.toSet()
		deleteCollections(doomed)
		return doomed
	}

	private fun versionsOf(alias: String): List<Pair<String, String>> =
		http.get().uri("/collections").retrieve()
			.requiredBody<CollectionsResponse>().result.collections
			.mapNotNull { c -> IndexVersion.tokenOf(alias, c.name)?.let { c.name to it } }

	fun count(collection: String): Long =
		http.post().uri("/collections/{name}/points/count", collection)
			.body(mapOf("exact" to true))
			.retrieve().requiredBody<CountResponse>().result.count

	fun upsert(collection: String, points: List<VectorPoint>) {
		if (points.isEmpty()) return
		http.put().uri("/collections/{name}/points?wait=true", collection)
			.body(
				mapOf(
					"points" to points.map {
						mapOf(
							"id" to QdrantContract.pointId(it.placeId),
							"vector" to it.vector.toList(),
							"payload" to it.payload,
						)
					},
				),
			)
			.retrieve().toBodilessEntity()
	}

	fun delete(collection: String, placeIds: List<String>) {
		if (placeIds.isEmpty()) return
		http.post().uri("/collections/{name}/points/delete?wait=true", collection)
			.body(mapOf("points" to placeIds.map { QdrantContract.pointId(it) }))
			.retrieve().toBodilessEntity()
	}

	fun maxUpdatedAt(collection: String): OffsetDateTime? =
		http.post().uri("/collections/{name}/points/scroll", collection)
			.body(
				mapOf(
					"limit" to 1,
					"with_payload" to listOf(QdrantContract.UPDATED_AT),
					"order_by" to mapOf("key" to QdrantContract.UPDATED_AT, "direction" to "desc"),
				),
			)
			.retrieve().requiredBody<ScrollResponse>()
			.result.points.firstOrNull()
			?.payload?.get(QdrantContract.UPDATED_AT)
			?.let { OffsetDateTime.parse(it.toString()) }

	private fun aliases(): List<AliasDescription> =
		http.get().uri("/aliases").retrieve().requiredBody<AliasesResponse>().result.aliases
}

internal data class AliasesResponse(val result: AliasList)
internal data class AliasList(val aliases: List<AliasDescription>)
internal data class AliasDescription(val alias_name: String, val collection_name: String)
internal data class ScrollResponse(val result: ScrollResult)
internal data class ScrollResult(val points: List<ScrollPoint>)
internal data class ScrollPoint(val payload: Map<String, Any?> = emptyMap())
internal data class CollectionsResponse(val result: CollectionList)
internal data class CollectionList(val collections: List<CollectionDescription>)
internal data class CollectionDescription(val name: String)
internal data class CountResponse(val result: CountResult)
internal data class CountResult(val count: Long)
