package io.kotatsuredo.server.works

/**
 * What a client saw. Everything is optional except the source, its key and one title, because
 * different parsers expose wildly different amounts of metadata.
 */
data class WorkFingerprint(
	val source: String,
	/** The parser's url or slug - never `Manga.id`, which changes if parser hashing changes. */
	val sourceKey: String,
	val title: String,
	val altTitles: List<String> = emptyList(),
	val year: Int? = null,
	val contentType: String? = null,
	val nsfw: Boolean = false,
	/** From the app's `CoverHash`: DCT-based, 63 bits, edge-trimmed. */
	val coverPHash: Long? = null,
	/** `mal` / `anilist` / `kitsu` / `mangaupdates`, when the user has a scrobbler linked. */
	val externalIds: Map<String, String> = emptyMap(),
) {
	val allTitles: List<String> get() = (listOf(title) + altTitles).filter { it.isNotBlank() }
}

object WorkLimits {
	const val MIN_YEAR = 1_000
	const val MAX_YEAR = 3_000

	fun isValidYear(year: Int?): Boolean = year == null || year in MIN_YEAR..MAX_YEAR
}

/** How a work was arrived at. Recorded on the alias so a bad rule can be found and undone later. */
enum class ResolutionMethod(val confidence: Double) {
	ALIAS(1.0),
	OBSERVATION(0.5),
	EXTERNAL_ID(1.0),
	EXACT_TITLE(0.95),
	CATALOGUE(0.95),
	TITLE_AND_COVER(0.90),
	/** Linked, but flagged: a title match with nothing corroborating it. */
	FUZZY_TITLE(0.70),
	CREATED(0.25),
}

data class Resolution(
	val workId: Long,
	val method: ResolutionMethod,
	val created: Boolean,
) {
	val needsReview: Boolean get() = method == ResolutionMethod.FUZZY_TITLE
}
