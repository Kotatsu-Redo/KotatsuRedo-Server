package io.kotatsuredo.server.works

import java.text.Normalizer
import java.util.Locale

/**
 * Turns a title into the key that decides whether two sources are talking about the same work.
 *
 * This is the most load-bearing function in the system. Two rules do almost all the work, and they
 * pull in opposite directions on purpose (PLAN.md §2.2, §2.5):
 *
 *  - **edition markers are stripped**, so "Chainsaw Man (Colored)" and "Chainsaw Man" merge;
 *  - **sequence markers are preserved**, so "Tower of God" and "Tower of God Part 2" never do.
 *
 * The second is the more important of the two. An unmerged pair is a mild annoyance; a sequel merged
 * into its prequel silently mixes two communities and leaks spoilers into a thread where nobody has
 * read that far.
 */
object TitleNormalizer {

	/**
	 * Words that describe a *printing* rather than a story. Removing them is what makes editions
	 * collapse onto one work.
	 */
	private val EDITION_TOKENS = setOf(
		"official", "officially", "colored", "coloured", "color", "colour", "full color", "full colour",
		"fan colored", "fan coloured", "digital", "digitally", "raw", "raws", "remastered", "remaster",
		// A redrawn release of the same story: sources list "X (Remake 2022)" beside plain "X".
		"remake",
		"webtoon", "webcomic", "novel", "light novel", "doujinshi", "doujin", "manhwa", "manhua",
		"uncensored", "censored", "scanlation", "scan", "scans", "hd", "reprint", "omnibus",
		"complete", "completed", "ongoing", "oneshot", "one shot", "anthology", "tankoubon",
	)

	/**
	 * Words that introduce a *different story in the same series*. Anything following one of these is
	 * part of the identity and survives normalization.
	 */
	private val SEQUENCE_WORDS = setOf("part", "season", "arc", "book", "volume", "vol", "chapter", "act", "stage")

	private val ROMAN_NUMERALS = mapOf(
		"i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8,
		"ix" to 9, "x" to 10, "xi" to 11, "xii" to 12,
	)

	private val SPELLED_NUMBERS = mapOf(
		"first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
		"seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
		"one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
		"eight" to 8, "nine" to 9, "ten" to 10,
	)

	private val LANGUAGE_WORDS = setOf(
		"english", "japanese", "korean", "chinese", "spanish", "french", "german", "italian",
		"portuguese", "russian", "indonesian", "vietnamese", "thai", "turkish", "polish", "arabic",
	)

	/** Macron and long-vowel expansions. Hepburn disagreement is a top cause of missed matches. */
	private val MACRONS = mapOf('ō' to "ou", 'ū' to "uu", 'ā' to "aa", 'ē' to "ee", 'ī' to "ii")

	private val BRACKETED = Regex("""[(\[{][^)\]}]*[)\]}]""")
	private val YEAR = Regex("""\b(1[89]\d{2}|20\d{2})\b""")
	private val WHITESPACE = Regex("""\s+""")

	/**
	 * All keys a title should be indexed and searched under.
	 *
	 * Usually one. Latin titles containing a macron produce two - the accent-stripped form and the
	 * vowel-expanded one - because sources disagree about which romanization to use and generating
	 * both is far cheaper than guessing right ("Ōsama Ranking" is indexed as both `osama ranking` and
	 * `ousama ranking`).
	 */
	fun keys(raw: String): Set<String> {
		val cleaned = clean(raw)
		if (cleaned.isEmpty()) return emptySet()

		val stripped = finish(stripMarks(cleaned))
		val expanded = finish(expandMacrons(cleaned))

		return buildSet {
			addIfNotEmpty(stripped)
			addIfNotEmpty(expanded)
			// Romanized Japanese and Korean have no canonical word segmentation, and sources disagree
			// about it constantly: "Kagurabachi" / "Kagura-bachi", "Dandadan" / "DAN DA DAN",
			// "Kamitachi" / "Kami-tachi". Measured against the evaluation fixture this single extra key
			// is the difference between matching half of the punctuation-only variants and matching all
			// of them, and it costs nothing in precision - two different works having the same letters
			// in the same order once spaces are removed is vanishingly rare.
			//
			// Guarded by a minimum length so that short titles cannot collide by accident.
			if (stripped.length >= MIN_SPACELESS_LENGTH) addIfNotEmpty(stripped.replace(" ", ""))
			if (expanded.length >= MIN_SPACELESS_LENGTH) addIfNotEmpty(expanded.replace(" ", ""))
		}
	}

	private fun MutableSet<String>.addIfNotEmpty(value: String) {
		if (value.isNotEmpty()) add(value)
	}

	private const val MIN_SPACELESS_LENGTH = 8

	/** The single primary key for a title: the accent-stripped, space-preserving form. */
	fun normalize(raw: String): String = finish(stripMarks(clean(raw)))

	/**
	 * True when two titles differ *only* by edition markers - that is, they should merge. Used by the
	 * resolver as the guard in §2.5: equal keys are not enough on their own if the originals
	 * disagreed about a sequence marker.
	 */
	fun differsOnlyByEdition(a: String, b: String): Boolean =
		normalize(a) == normalize(b) && sequenceSignature(a) == sequenceSignature(b)

	/**
	 * The part of a title that says *which* entry in a series it is, as a comparable string.
	 *
	 * Empty for a first entry. "Tower of God Part 2" gives "part 2", so it can never collide with the
	 * original however aggressively the rest is normalized.
	 */
	fun sequenceSignature(raw: String): String {
		val tokens = clean(raw).let(::stripMarks).split(' ').filter { it.isNotEmpty() }
		val parts = mutableListOf<String>()
		tokens.forEachIndexed { index, token ->
			if (token in SEQUENCE_WORDS) {
				val next = tokens.getOrNull(index + 1)?.let(::asNumber)
				if (next != null) parts += "$token $next"
			}
		}
		// A bare trailing numeral is a sequence marker too: "Kingdom 2" is not "Kingdom".
		tokens.lastOrNull()?.let { last ->
			val n = asNumber(last)
			if (n != null && n > 1 && parts.isEmpty() && tokens.size > 1) parts += n.toString()
		}
		return parts.joinToString(" ")
	}

	private fun asNumber(token: String): Int? =
		token.toIntOrNull() ?: ROMAN_NUMERALS[token] ?: SPELLED_NUMBERS[token]

	/** Steps 1-3: fold width and case, drop edition noise, canonicalize sequence markers. */
	private fun clean(raw: String): String {
		// NFKC folds full-width CJK and most compatibility variants for free.
		var s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

		// A bracketed group is dropped only when it is *entirely* edition noise. Dropping every
		// bracketed group would eat real subtitles, which frequently carry the story's identity.
		s = BRACKETED.replace(s) { match ->
			val inner = match.value.trim('(', ')', '[', ']', '{', '}').trim()
			if (isEditionNoise(inner)) " " else " ${match.value.trim('(', ')', '[', ']', '{', '}')} "
		}

		s = s.replace(YEAR, " ")
		s = punctuationToSpace(s)

		val tokens = s.split(' ').filter { it.isNotEmpty() }.toMutableList()

		// Edition words are only stripped from the *end* of a title, never from the middle.
		//
		// A blanket removal is tempting and wrong: "colour" and "complete" are ordinary English words
		// that appear inside real titles, and stripping them everywhere would quietly merge unrelated
		// works. At the end of a string they are almost always a printing note.
		while (tokens.isNotEmpty() && (tokens.last() in EDITION_TOKENS || tokens.last() in LANGUAGE_WORDS)) {
			tokens.removeAt(tokens.lastIndex)
		}

		return canonicalizeSequences(tokens).joinToString(" ")
	}

	private fun isEditionNoise(inner: String): Boolean {
		if (inner.isEmpty()) return true
		val tokens = punctuationToSpace(inner).split(' ').filter { it.isNotEmpty() }
		if (tokens.isEmpty()) return true
		// Check the whole phrase first: several entries are multi-word ("full color", "light novel")
		// and would never match token by token.
		if (tokens.joinToString(" ") in EDITION_TOKENS) return true
		return tokens.all { token ->
			token in EDITION_TOKENS || token in LANGUAGE_WORDS || YEAR.matches(token)
		}
	}

	/** `part ii` -> `part 2`, `second season` -> `season 2`, so the same entry keys identically. */
	private fun canonicalizeSequences(tokens: List<String>): List<String> {
		val result = mutableListOf<String>()
		var index = 0
		while (index < tokens.size) {
			val token = tokens[index]
			val next = tokens.getOrNull(index + 1)
			when {
				token in SEQUENCE_WORDS && next != null && asNumber(next) != null -> {
					result += token
					result += asNumber(next).toString()
					index += 2
				}
				// "second season" reads as "season 2".
				asNumber(token) != null && next != null && next in SEQUENCE_WORDS -> {
					result += next
					result += asNumber(token).toString()
					index += 2
				}

				else -> {
					result += token
					index++
				}
			}
		}
		return result
	}

	/**
	 * Apostrophes are **deleted**, everything else becomes a space.
	 *
	 * Sources write both "JoJo's Bizarre Adventure" and "JoJos Bizarre Adventure". Turning the
	 * apostrophe into a space would give "jojo s bizarre adventure" and the two would never match.
	 */
	private fun punctuationToSpace(value: String): String = buildString(value.length) {
		value.forEach { c ->
			when {
				c in APOSTROPHES -> Unit
				c.isLetterOrDigit() || c.isWhitespace() -> append(c)
				else -> append(' ')
			}
		}
	}

	private val APOSTROPHES = setOf('\'', '’', 'ʼ', '՚', '`')

	private fun stripMarks(value: String): String =
		Normalizer.normalize(value, Normalizer.Form.NFD)
			.filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

	private fun expandMacrons(value: String): String = buildString(value.length) {
		value.forEach { c -> append(MACRONS[c] ?: c.toString()) }
	}

	private fun finish(value: String): String = WHITESPACE.replace(value, " ").trim()
}
