package io.kotatsuredo.server.catalogue

import io.kotatsuredo.server.works.TitleNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("MangaUpdatesCatalogue")

/**
 * MangaUpdates, the fallback catalogue.
 *
 * Second in order rather than first purely because it costs two round trips: the search endpoint does
 * not return associated names, so the alt titles - the only field that really matters for resolution -
 * need a follow-up call per series.
 *
 * Worth the extra call when Kitsu misses, because its long-tail coverage of manhwa, manhua and
 * scanlation-only series is considerably better, and those are exactly the works a manga reader with
 * 1200 sources will be looking at.
 */
class MangaUpdatesCatalogue(
	private val fetcher: HttpFetcher,
	private val baseUrl: String = "https://api.mangaupdates.com/v1",
) : CatalogueProvider {

	override val name = PROVIDER

	private val json = Json { ignoreUnknownKeys = true }

	override suspend fun lookup(title: String, year: Int?): CatalogueRecord? {
		val searchBody = """{"search":${quote(title)},"perpage":$CANDIDATE_LIMIT}"""
		val searchResponse = fetcher.fetch("$baseUrl/series/search", searchBody) ?: return null

		val seriesId = runCatching { pickSeries(searchResponse, title, year) }
			.onFailure { log.warn("Failed to parse MangaUpdates search response", it) }
			.getOrNull() ?: return null

		val detail = fetcher.get("$baseUrl/series/$seriesId") ?: return null
		return runCatching { parseSeries(detail) }
			.onFailure { log.warn("Failed to parse MangaUpdates series {}", seriesId, it) }
			.getOrNull()
	}

	/**
	 * The search endpoint only exposes the primary title, so candidate selection has to work from
	 * that alone. A normalized-key hit is required; a year match is accepted as a weaker fallback.
	 */
	fun pickSeries(body: String, wantedTitle: String, year: Int?): String? {
		val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return null
		val wantedKeys = TitleNormalizer.keys(wantedTitle)

		val records = results.mapNotNull { it.jsonObject["record"]?.jsonObject }
		val exact = records.firstOrNull { record ->
			record.str("title")?.let { TitleNormalizer.keys(it).any { key -> key in wantedKeys } } == true
		}
		val chosen = exact ?: records.firstOrNull { record ->
			year != null && record["year"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() == year
		}
		return chosen?.get("series_id")?.jsonPrimitive?.contentOrNull
	}

	fun parseSeries(body: String): CatalogueRecord? {
		val record = json.parseToJsonElement(body).jsonObject
		val canonical = record.str("title") ?: return null
		val seriesId = record["series_id"]?.jsonPrimitive?.contentOrNull ?: return null

		val titles = buildList {
			add(canonical)
			record["associated"]?.jsonArray?.forEach { entry ->
				entry.jsonObject.str("title")?.takeIf { it.isNotBlank() }?.let(::add)
			}
		}.distinct()

		val relations = record["related_series"]?.jsonArray.orEmpty().mapNotNull { entry ->
			val obj = entry.jsonObject
			val type = obj.str("relation_type")?.let(::mapRelation) ?: return@mapNotNull null
			CatalogueRelation(
				type = type,
				targetTitle = obj.str("related_series_name"),
				targetId = obj["related_series_id"]?.jsonPrimitive?.contentOrNull,
			)
		}

		return CatalogueRecord(
			provider = PROVIDER,
			externalId = seriesId,
			canonicalTitle = canonical,
			titles = titles,
			year = record["year"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
				?: record["year"]?.jsonPrimitive?.intOrNull,
			contentType = record.str("type"),
			// MangaUpdates marks adult content through genres rather than a flag.
			nsfw = record["genres"]?.jsonArray.orEmpty().any { genre ->
				genre.jsonObject.str("genre")?.lowercase() in ADULT_GENRES
			},
			externalIds = mapOf(PROVIDER to seriesId),
			relations = relations,
		)
	}

	/**
	 * `Colored` and `Alternate Story` are edition variants, which §2.5 merges rather than splits, so
	 * they are not returned as relations - a relation would record them as separate works.
	 */
	private fun mapRelation(relationType: String): String? = when (relationType.lowercase()) {
		"sequel" -> "sequel"
		"prequel" -> "prequel"
		"side story", "spin-off", "spin off" -> "side_story"
		"alternate version", "adaptation" -> "alternative_version"
		else -> null
	}

	private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

	private fun quote(value: String): String =
		"\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

	private companion object {
		const val PROVIDER = "mangaupdates"
		const val CANDIDATE_LIMIT = 5
		val ADULT_GENRES = setOf("adult", "hentai", "smut")
	}
}
