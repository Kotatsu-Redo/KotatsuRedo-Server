package io.kotatsuredo.server.catalogue

/**
 * One work as an external catalogue describes it.
 *
 * [titles] is the field that matters. Resolution is driven by exact normalized-key collision, so the
 * value of a catalogue is almost entirely "how many renderings of this title does it know" - a
 * catalogue listing `Chainsaw Man`, `Chainsawman`, `Chain saw man` and `チェンソーマン` resolves four
 * different sources; one listing only the first resolves one.
 */
data class CatalogueRecord(
	val provider: String,
	val externalId: String,
	val canonicalTitle: String,
	val titles: List<String>,
	val year: Int?,
	val contentType: String?,
	val nsfw: Boolean,
	/** provider -> id, e.g. `myanimelist` -> `116778`. Anchors for scrobbler-linked clients. */
	val externalIds: Map<String, String>,
	val relations: List<CatalogueRelation> = emptyList(),
)

data class CatalogueRelation(val type: String, val targetTitle: String?, val targetId: String?)

/**
 * A source of manga metadata.
 *
 * Deliberately narrow: given what a client saw, find the work. Providers are tried in order and the
 * first confident answer wins, so adding one is additive and removing one degrades rather than breaks.
 */
interface CatalogueProvider {

	val name: String

	/**
	 * @param title what the source called it
	 * @param year the source's year, when it has one - used only to reject a confident-looking
	 * mismatch, never to require agreement, because sources disagree about publication years often.
	 * @param contentType what the source says this is (`manga`, `manhwa`, ...). Catalogues list a
	 * novel and its comic adaptation under one title, so without this the first hit wins and a manga
	 * source can be handed the novel - a work every later comic source is then incompatible with.
	 * @throws CatalogueUnavailable when the provider could not be reached or answered unusably. A
	 * genuine "not found" returns null; the two must not be confused, because only the first is worth
	 * remembering.
	 */
	suspend fun lookup(title: String, year: Int? = null, contentType: String? = null): CatalogueRecord?
}

/** The provider did not answer. Never cached: the work may well be there once it is back. */
class CatalogueUnavailable(provider: String) : RuntimeException("catalogue $provider is unavailable")

/** Injectable so provider parsing can be tested against captured responses rather than the network. */
fun interface HttpFetcher {
	/**
	 * @param jsonBody null for GET, a body for POST. No default value: a functional interface's
	 * abstract method cannot have one.
	 * @param accept the media type to ask for. Not a constant, because JSON is not one thing: Kitsu
	 * speaks JSON:API and answers `406 Not Acceptable` to a plain `application/json` - silently, for
	 * months, because a catalogue that refuses every request looks exactly like one that has never
	 * heard of the title.
	 * @return response body, or null on any failure - a catalogue being down is never fatal.
	 */
	suspend fun fetch(url: String, jsonBody: String?, accept: String): String?
}

const val ACCEPT_JSON = "application/json"
const val ACCEPT_JSON_API = "application/vnd.api+json"

suspend fun HttpFetcher.get(url: String, accept: String = ACCEPT_JSON): String? = fetch(url, null, accept)

suspend fun HttpFetcher.post(url: String, jsonBody: String, accept: String = ACCEPT_JSON): String? =
	fetch(url, jsonBody, accept)

/**
 * The same series id, written two ways.
 *
 * Kitsu and MangaDex both record a MangaUpdates mapping the way the website's URL spells it - base36,
 * as in `/series/xpnodiq/` - while the MangaUpdates API answers in decimal, `73385239922`. They are
 * one number, so storing that spelling verbatim meant an id from one provider could never meet the
 * same id from another: no cross-provider match, ever, and duplicates that nothing could join.
 *
 * Anything that is not a clean base36 word is left exactly as it came.
 */
fun canonicalMangaUpdatesId(id: String): String {
	if (id.isEmpty() || id.all(Char::isDigit)) return id
	if (id.length > MAX_MANGAUPDATES_BASE36 || !id.all { it.isDigit() || it in 'a'..'z' }) return id
	return runCatching { id.toLong(radix = 36).toString() }.getOrDefault(id)
}


/** A MangaUpdates id is 11 decimal digits at most, which is seven base36 characters. */
private const val MAX_MANGAUPDATES_BASE36 = 8
