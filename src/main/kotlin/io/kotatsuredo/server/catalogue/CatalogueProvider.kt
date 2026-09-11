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
	 */
	suspend fun lookup(title: String, year: Int? = null): CatalogueRecord?
}

/** Injectable so provider parsing can be tested against captured responses rather than the network. */
fun interface HttpFetcher {
	/**
	 * @param jsonBody null for GET, a body for POST. No default value: a functional interface's
	 * abstract method cannot have one.
	 * @return response body, or null on any failure - a catalogue being down is never fatal.
	 */
	suspend fun fetch(url: String, jsonBody: String?): String?
}

suspend fun HttpFetcher.get(url: String): String? = fetch(url, null)
