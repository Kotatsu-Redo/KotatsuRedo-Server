package io.kotatsuredo.server.works

/**
 * The genres this server understands, and nothing else.
 *
 * Categories come from what readers' sources say, which means they are user-supplied strings from a
 * thousand parsers: misspellings, translations, site-specific labels, and occasionally abuse. A
 * closed vocabulary is what makes that safe to accept without a moderator in the loop - an unknown
 * term is dropped on arrival rather than queued, published or shown to anyone.
 *
 * The set is deliberately small and common. It is not an attempt to describe every work precisely;
 * it is the handful of labels that are worth matching two works on, and that almost every source
 * agrees about.
 */
object Genres {

	/** The published vocabulary. Adding a term is a deploy, which is the point. */
	val CANONICAL: Set<String> = setOf(
		"action",
		"adventure",
		"comedy",
		"cooking",
		"crime",
		"drama",
		"ecchi",
		"fantasy",
		"harem",
		"historical",
		"horror",
		"isekai",
		"josei",
		"magic",
		"martial-arts",
		"mecha",
		"medical",
		"military",
		"music",
		"mystery",
		"philosophical",
		"post-apocalyptic",
		"psychological",
		"reincarnation",
		"revenge",
		"romance",
		"school-life",
		"sci-fi",
		"seinen",
		"shoujo",
		"shounen",
		"slice-of-life",
		"sports",
		"superhero",
		"supernatural",
		"survival",
		"thriller",
		"time-travel",
		"tragedy",
		"villainess",
		"boys-love",
		"girls-love",
		"adult",
	)

	/**
	 * What other people call the same thing.
	 *
	 * Demographics (`shounen`, `josei`) sit beside genres because sources list them in the same
	 * field, and a reader looking for more of the same means them the same way.
	 */
	private val ALIASES: Map<String, String> = mapOf(
		"science fiction" to "sci-fi",
		"scifi" to "sci-fi",
		"sf" to "sci-fi",
		"slice of life" to "slice-of-life",
		"school" to "school-life",
		"school life" to "school-life",
		"martial arts" to "martial-arts",
		"shonen" to "shounen",
		"shounen ai" to "boys-love",
		"shonen ai" to "boys-love",
		"yaoi" to "boys-love",
		"bl" to "boys-love",
		"boys love" to "boys-love",
		"shoujo ai" to "girls-love",
		"shojo ai" to "girls-love",
		"yuri" to "girls-love",
		"gl" to "girls-love",
		"girls love" to "girls-love",
		"shojo" to "shoujo",
		"mature" to "adult",
		"smut" to "adult",
		"hentai" to "adult",
		"erotica" to "adult",
		"magical girls" to "magic",
		"magical" to "magic",
		"time travel" to "time-travel",
		"post apocalyptic" to "post-apocalyptic",
		"apocalypse" to "post-apocalyptic",
		"suspense" to "thriller",
		"detective" to "mystery",
		"gourmet" to "cooking",
		"food" to "cooking",
		"sport" to "sports",
		"super power" to "superhero",
		"superpowers" to "superhero",
		"super powers" to "superhero",
		"reverse harem" to "harem",
		"transported to another world" to "isekai",
		"another world" to "isekai",
		"reincarnated" to "reincarnation",
		"rebirth" to "reincarnation",
		"war" to "military",
		"mecha robots" to "mecha",
		"robots" to "mecha",
		"psychological thriller" to "psychological",
	)

	/**
	 * Maps one raw label onto the vocabulary, or null when nothing fits.
	 *
	 * Null is the common case and the safe one: a source's own oddities never reach anybody.
	 */
	fun parse(raw: String): String? {
		val normalized = raw.trim().lowercase()
			.replace('&', ' ')
			.replace(Regex("[^a-z0-9]+"), " ")
			.trim()
			.replace(Regex("\\s+"), " ")
		if (normalized.isEmpty() || normalized.length > MAX_LABEL_LENGTH) return null

		val hyphenated = normalized.replace(' ', '-')
		return when {
			hyphenated in CANONICAL -> hyphenated
			normalized in ALIASES -> ALIASES.getValue(normalized)
			else -> null
		}
	}

	/** Maps a source's whole genre list, dropping what it does not recognise. */
	fun parseAll(raw: Collection<String>): List<String> =
		raw.mapNotNull(::parse).distinct().take(MAX_GENRES_PER_WORK)

	/** Long enough for "transported to another world", short enough to reject a sentence. */
	private const val MAX_LABEL_LENGTH = 40

	/**
	 * A work described by twenty genres is described by none, and this also bounds what one
	 * request can assert.
	 */
	const val MAX_GENRES_PER_WORK = 10
}
