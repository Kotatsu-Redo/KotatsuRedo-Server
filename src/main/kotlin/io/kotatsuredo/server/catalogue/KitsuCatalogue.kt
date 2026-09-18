package io.kotatsuredo.server.catalogue

import io.kotatsuredo.server.works.TitleNormalizer
import io.kotatsuredo.server.works.WorkCompatibility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = LoggerFactory.getLogger("KitsuCatalogue")

/**
 * Kitsu, the primary catalogue.
 *
 * Chosen for one operational reason: a single request returns both the title renderings and the
 * external ids for MyAnimeList, AniList *and* MangaUpdates. MangaUpdates needs two round trips for
 * the same information, and AniList - the obvious first choice - was found returning
 * `"The AniList API has been temporarily disabled due to severe stability issues"`, which is exactly
 * the kind of dependency a seed must not have.
 *
 * Open, unauthenticated, JSON:API.
 */
class KitsuCatalogue(
	private val fetcher: HttpFetcher,
	private val baseUrl: String = "https://kitsu.app/api/edge",
) : CatalogueProvider {

	override val name = PROVIDER

	private val json = Json { ignoreUnknownKeys = true }

	override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord? {
		val query = URLEncoder.encode(title, StandardCharsets.UTF_8)
		val url = "$baseUrl/manga?filter[text]=$query&page[limit]=$CANDIDATE_LIMIT&include=mappings"
		// JSON:API, not JSON. Asking for the wrong one is a 406 on every single request.
		val body = fetcher.get(url, ACCEPT_JSON_API) ?: throw CatalogueUnavailable(name)

		return runCatching { parse(body, title, year, contentType) }
			.onFailure { log.warn("Failed to parse Kitsu response", it) }
			.getOrNull()
	}

	fun parse(
		body: String,
		wantedTitle: String,
		year: Int? = null,
		contentType: String? = null,
	): CatalogueRecord? {
		val root = json.parseToJsonElement(body).jsonObject
		val data = root["data"]?.jsonArray ?: return null
		if (data.isEmpty()) return null

		val mappingsById = root["included"]?.jsonArray.orEmpty()
			.mapNotNull { it.jsonObject.takeIf { obj -> obj.str("type") == "mappings" } }
			.mapNotNull { mapping ->
				val mappingId = mapping.str("id") ?: return@mapNotNull null
				val attrs = mapping["attributes"]?.jsonObject ?: return@mapNotNull null
				val site = attrs.str("externalSite") ?: return@mapNotNull null
				val id = attrs.str("externalId") ?: return@mapNotNull null
				mappingId to (site to id)
			}
			.toMap()

		val wantedKeys = TitleNormalizer.keys(wantedTitle)

		// Kitsu's text filter is fuzzy, so the top hit is not necessarily the right work. Prefer a
		// candidate that actually shares a normalized key with what the source called it; only fall
		// back to the first result when nothing does, and mark that case by requiring the year to
		// agree if we have one.
		val all = data.mapNotNull { element -> element.jsonObject.toRecord(mappingsById) }
		// Kitsu lists "Past Life Returner" as both a novel and the manhwa drawn from it, novel first.
		// Handing a comic source the novel makes a work every later comic source is incompatible with,
		// so a candidate whose subtype cannot be what the source is reading is not a candidate at all.
		val candidates = all
			.filter { WorkCompatibility.compatibleContentTypes(it.contentType, contentType) }
			.ifEmpty { all }

		return candidates.firstOrNull { candidate ->
			candidate.titles.any { TitleNormalizer.keys(it).any { key -> key in wantedKeys } }
		} ?: candidates.firstOrNull { candidate ->
			year != null && candidate.year == year
		}
	}

	private fun JsonObject.toRecord(mappings: Map<String, Pair<String, String>>): CatalogueRecord? {
		val attributes = this["attributes"]?.jsonObject ?: return null
		val canonical = attributes.str("canonicalTitle") ?: return null

		val titles = buildList {
			add(canonical)
			attributes["titles"]?.jsonObject?.values?.forEach { value ->
				value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let(::add)
			}
			// The richest field Kitsu has: for Chainsaw Man it carries "Chainsawman", "CSM" and the
			// Cyrillic, Arabic, Korean and Chinese renderings, each of which is a source somewhere.
			attributes["abbreviatedTitles"]?.jsonArray?.forEach { value ->
				value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let(::add)
			}
		}.distinct()

		return CatalogueRecord(
			provider = PROVIDER,
			externalId = str("id") ?: return null,
			canonicalTitle = canonical,
			titles = titles,
			year = attributes.str("startDate")?.take(4)?.toIntOrNull(),
			contentType = attributes.str("subtype"),
			nsfw = attributes["nsfw"]?.jsonPrimitive?.contentOrNull == "true",
			externalIds = buildMap {
				val mappingIds = this@toRecord["relationships"]?.jsonObject
					?.get("mappings")?.jsonObject
					?.get("data")?.jsonArray
					.orEmpty()
					.mapNotNull { it.jsonObject.str("id") }
				mappingIds.mapNotNull(mappings::get).forEach { (site, id) ->
					when {
						site.startsWith("myanimelist") -> put("mal", id)
						site.startsWith("anilist") -> put("anilist", id)
						site.startsWith("mangaupdates") -> put("mangaupdates", canonicalMangaUpdatesId(id))
					}
				}
			},
		)
	}

	private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

	private companion object {
		const val PROVIDER = "kitsu"

		/** Enough to look past a bad first hit without turning one lookup into a scan. */
		const val CANDIDATE_LIMIT = 5
	}
}
