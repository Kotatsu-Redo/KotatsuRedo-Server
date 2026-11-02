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
	/**
	 * What the source lists this work's genres as. Raw labels: the server maps them onto its own
	 * closed vocabulary and drops the rest, so a parser's oddities cost nothing.
	 */
	val genres: List<String> = emptyList(),
	/** Where the source keeps this work's cover, if it is an address other readers could safely be sent to. */
	val coverUrl: String? = null,
) {
	val allTitles: List<String> get() = (listOf(title) + altTitles).filter { it.isNotBlank() }
}

/** What may be kept as a cover address. Everything else is dropped rather than refused: it is only ever an extra. */
object CoverUrls {

	private const val MAX_LENGTH = 1024
	/** A last label a browser's URL parser reads as a number makes the whole host an IPv4 address: `0x7f.1`, `127.1`. */
	private val NUMERIC_LABEL = Regex("[0-9]+|0x[0-9a-f]*")
	private val PRIVATE_HOST_SUFFIXES = listOf(".local", ".internal", ".localhost", ".lan", ".home.arpa", ".intranet")

	fun clean(raw: String?): String? {
		val url = raw?.trim()?.takeIf { it.length in 1..MAX_LENGTH } ?: return null
		if (url.any { it.isWhitespace() || it.isISOControl() }) return null
		val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
		val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
		val isPublicHost = '.' in host &&
			!host.startsWith("[") &&
			!NUMERIC_LABEL.matches(host.substringAfterLast('.')) &&
			PRIVATE_HOST_SUFFIXES.none(host::endsWith)
		val isPlainHttps = uri.scheme.equals("https", ignoreCase = true) &&
			uri.userInfo == null &&
			uri.port in setOf(-1, 443)
		return url.takeIf { isPublicHost && isPlainHttps }
	}
}

object WorkLimits {
	const val MIN_YEAR = 1_000
	const val MAX_YEAR = 3_000

	fun isValidYear(year: Int?): Boolean = year == null || year in MIN_YEAR..MAX_YEAR

	/**
	 * The app's placeholder sources. Their keys are device file paths or nothing at all, so the same key
	 * means different things on different phones and a title is a folder name - never a work to resolve.
	 */
	val NON_NETWORK_SOURCES = setOf("LOCAL", "UNKNOWN", "TEST")
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
