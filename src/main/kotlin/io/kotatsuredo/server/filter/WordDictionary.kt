package io.kotatsuredo.server.filter

/**
 * "Is this containing token itself a known word?"
 *
 * The rule that kills essentially the whole Scunthorpe class without any context model (PLAN.md §6):
 *
 * > Block only if the match is the **whole token**, or the match is a proper substring of a token
 * > that is **not itself a known word**.
 *
 * `assassin` contains `ass` and is a word, so it is allowed. `Scunthorpe` contains `cunt` and is a
 * place name, so it is allowed. `fuck` *is* the token, so the dictionary never gets a say - which is
 * the critical detail, because spellcheck dictionaries contain profanity too and "is it a word"
 * alone would allow everything.
 */
fun interface WordDictionary {

	fun contains(token: String): Boolean

	companion object {
		/**
		 * Knows nothing, so every substring match blocks.
		 *
		 * Never use this in production: it is what makes `assassin` unpostable.
		 */
		val Empty = WordDictionary { false }

		fun of(words: Set<String>) = WordDictionary { it in words }
	}
}

/**
 * The bundled word lists, loaded from resources.
 *
 * Plain base forms rather than Hunspell `.dic`/`.aff` pairs: parsing affix rules properly is its own
 * project, and the affix-poor languages are exactly the Latin ones where the containing-word check
 * matters most. The cost is inflected forms in agglutinative languages - a Turkish word with three
 * suffixes will not be found, its block will stand, and the feedback loop is what catches it. That
 * trade is recorded here rather than discovered later.
 *
 * Missing words fail *closed* (the block stands), which is the safe direction for a strict posture
 * and the wrong direction for false positives, so the panel's block queue is not optional.
 */
class ResourceDictionary private constructor(private val words: Set<String>) : WordDictionary {

	/**
	 * Known, or a known word with an ordinary English ending on it.
	 *
	 * `passenger` was in the list and `passengers` was not, so `ass` blocked a sentence about trains -
	 * and that pattern repeats for every noun and verb in the language. Stripping a few suffixes
	 * covers the whole class in a way that enumerating plurals never will.
	 *
	 * It cannot rescue a genuine match: a blocklist term is not in this dictionary, so `fucking`
	 * still reduces to `fuck`, finds nothing, and stays blocked.
	 */
	override fun contains(token: String): Boolean {
		if (matches(token)) return true
		// `reclassified` from `classify`. Symmetrical with the suffix rule and catches the same kind
		// of miss: the dictionary knows the word, just not this form of it.
		for (prefix in PREFIXES) {
			if (token.startsWith(prefix) && token.length - prefix.length >= MIN_STEM) {
				if (matches(token.drop(prefix.length))) return true
			}
		}
		return false
	}

	private fun matches(token: String): Boolean {
		if (token in words) return true
		for (suffix in SUFFIXES) {
			if (!token.endsWith(suffix) || token.length - suffix.length < MIN_STEM) continue
			val stem = token.dropLast(suffix.length)
			if (stem in words) return true
			// `parties` -> `party`, `bodies` -> `body`.
			if (suffix == "es" && "${stem}y" in words) return true
			// `passes` -> `pass`, and the doubled-consonant past tenses.
			if (stem.length > MIN_STEM && stem.last() == stem[stem.length - 2] &&
				stem.dropLast(1) in words
			) {
				return true
			}
		}
		return false
	}

	val size: Int get() = words.size

	companion object {

		private val PREFIXES = listOf(
			"re", "un", "non", "pre", "over", "under", "mis", "dis", "inter", "anti", "sub", "super",
		)

		/** Ordered longest-first, so `-ations` is tried before `-s`. */
		private val SUFFIXES = listOf(
			"ations", "ation", "ingly", "ances", "ance", "ments", "ment", "ities", "ity",
			"ings", "ing", "ies", "ied", "ers", "er", "est", "ed", "es", "s", "ly", "al",
		)

		/** Below this a "stem" is not a word, it is a coincidence. */
		private const val MIN_STEM = 3

		/**
		 * `filter/words/<lang>.txt`, one word per line, `#` for comments.
		 *
		 * `severe-guards.txt` is always loaded alongside them. It holds the innocent words that
		 * *contain* a severe term - `raccoon`, `pakistani`, `suspicious` - and since the severe tier
		 * is global and matches as a substring, those have to be known whatever languages are
		 * configured. Leaving it out of this list is what blocked `raccoon`.
		 */
		fun load(languages: Collection<String>): ResourceDictionary {
			val words = mutableSetOf<String>()
			for (language in languages + SEVERE_GUARDS) {
				val stream = ResourceDictionary::class.java.getResourceAsStream("/filter/words/$language.txt")
					?: continue
				stream.bufferedReader().useLines { lines ->
					lines.forEach { line ->
						val trimmed = line.trim()
						if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
						words.add(FilterNormalizer.setA(trimmed))
					}
				}
			}
			return ResourceDictionary(words)
		}

		/** Not a language; a file of guards that must be present regardless of configuration. */
		private const val SEVERE_GUARDS = "severe-guards"
	}
}

/** Several dictionaries as one - bundled words, catalogue titles, and whatever else knows a word. */
class CompositeDictionary(private val parts: List<WordDictionary>) : WordDictionary {
	override fun contains(token: String): Boolean = parts.any { it.contains(token) }
}
