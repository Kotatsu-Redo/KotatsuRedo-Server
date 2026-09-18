package io.kotatsuredo.server.catalogue

import io.kotatsuredo.server.works.TitleNormalizer
import io.kotatsuredo.server.works.WorkCompatibility
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = LoggerFactory.getLogger("MangaDexCatalogue")

/**
 * MangaDex, asked first.
 *
 * Not for its hit rate - measured against works this instance's readers actually open, it answers a
 * third of them against Kitsu's quarter, which is a difference but not the reason. The reason is the
 * `links` object: one request returns the MyAnimeList, AniList, Kitsu *and* MangaUpdates ids for a
 * work. Those ids are what let two works found by two different sources turn out to be one, and the
 * absence of exactly that is why duplicates piled up (PLAN.md §2.6).
 *
 * It is also the catalogue that knows the scanlation long tail, which is most of what a reader with
 * 1200 sources is looking at.
 *
 * **Politeness is not optional here.** MangaDex allows roughly five requests a second per address,
 * and answers abuse by escalating: 429, then a temporary 403 ban, then blocking the address
 * outright. So requests are spaced by [minIntervalMillis] and a refusal is reported as
 * [CatalogueUnavailable] - the enricher then backs off and tries later, which is the one response
 * that cannot turn a busy minute into a ban.
 */
class MangaDexCatalogue(
	private val fetcher: HttpFetcher,
	private val baseUrl: String = "https://api.mangadex.org",
	private val minIntervalMillis: Long = MIN_INTERVAL_MS,
) : CatalogueProvider {

	override val name = PROVIDER

	private val json = Json { ignoreUnknownKeys = true }
	private val pace = Mutex()
	private var lastRequestAtMillis = 0L

	override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord? {
		val query = URLEncoder.encode(title.take(MAX_QUERY_LENGTH), StandardCharsets.UTF_8)
		// Every rating, or half the library is invisible: a reader's sources carry plenty that the
		// default `safe` filter hides, and a work we cannot see is a work we create a duplicate of.
		val ratings = CONTENT_RATINGS.joinToString("") { "&contentRating%5B%5D=$it" }
		val url = "$baseUrl/manga?limit=$CANDIDATE_LIMIT&title=$query$ratings"

		val body = paced { fetcher.get(url) } ?: throw CatalogueUnavailable(name)
		return runCatching { parse(body, title, year, contentType) }
			.onFailure { log.warn("Failed to parse MangaDex response", it) }
			.getOrNull()
	}

	/** Serialises requests and keeps them apart. One caller at a time is well inside the limit. */
	private suspend fun <T> paced(block: suspend () -> T): T = pace.withLock {
		val since = System.currentTimeMillis() - lastRequestAtMillis
		if (since < minIntervalMillis) delay(minIntervalMillis - since)
		try {
			block()
		} finally {
			lastRequestAtMillis = System.currentTimeMillis()
		}
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

		val wantedKeys = TitleNormalizer.keys(wantedTitle)
		val all = data.mapNotNull { it.jsonObject.toRecord() }
		val candidates = all
			.filter { WorkCompatibility.compatibleContentTypes(it.contentType, contentType) }
			.ifEmpty { all }

		// Searching "Chainsaw Man" returns the short story collection first, then the coloured
		// edition, then the work itself - and the edition normalizes to the same key as the work
		// (§2.5), so several candidates match. Among those that do, take the one carrying the most
		// cross-provider ids: editions are thin records, and the ids are the entire point of asking.
		val matched = candidates.filter { candidate ->
			candidate.titles.any { TitleNormalizer.keys(it).any { key -> key in wantedKeys } }
		}
		return matched.maxByOrNull { it.externalIds.size }
			?: candidates.firstOrNull { it.year != null && year != null && it.year == year }
	}

	private fun JsonObject.toRecord(): CatalogueRecord? {
		val id = str("id") ?: return null
		val attributes = this["attributes"]?.jsonObject ?: return null

		val titles = buildList {
			attributes["title"]?.jsonObject?.values?.forEach { value ->
				value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let(::add)
			}
			// Every language MangaDex knows the work by, which is the richest title source anywhere
			// and the reason a Korean source and a French one land on the same work.
			attributes["altTitles"]?.jsonArray?.forEach { entry ->
				entry.jsonObject.values.forEach { value ->
					value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let(::add)
				}
			}
		}.distinct()
		val canonical = titles.firstOrNull() ?: return null

		val links = attributes["links"]?.jsonObject
		return CatalogueRecord(
			provider = PROVIDER,
			externalId = id,
			canonicalTitle = canonical,
			titles = titles,
			year = attributes["year"]?.jsonPrimitive?.intOrNull,
			contentType = attributes.str("originalLanguage")?.let(::contentTypeOf),
			nsfw = attributes.str("contentRating") in ADULT_RATINGS,
			externalIds = buildMap {
				put(PROVIDER, id)
				links?.str("mal")?.let { put("mal", it) }
				links?.str("al")?.let { put("anilist", it) }
				links?.str("kt")?.let { put("kitsu", it) }
				links?.str("mu")?.let { put("mangaupdates", canonicalMangaUpdatesId(it)) }
			},
		)
	}

	/**
	 * What the work is, from the language it was drawn in. MangaDex has no "type" field, and this is
	 * the distinction the resolver actually uses: a manhwa and a manga are never the same work.
	 */
	private fun contentTypeOf(originalLanguage: String): String? = when (originalLanguage.lowercase()) {
		"ja" -> "manga"
		"ko" -> "manhwa"
		"zh", "zh-hk", "zh-ro" -> "manhua"
		else -> null
	}

	private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

	private companion object {
		const val PROVIDER = "mangadex"
		const val CANDIDATE_LIMIT = 5
		const val MAX_QUERY_LENGTH = 100

		/** Four a second at the very most, against a documented five. */
		const val MIN_INTERVAL_MS = 250L

		val CONTENT_RATINGS = listOf("safe", "suggestive", "erotica", "pornographic")
		val ADULT_RATINGS = setOf("erotica", "pornographic")
	}
}
