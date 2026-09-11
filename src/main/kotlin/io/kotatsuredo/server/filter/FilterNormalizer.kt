package io.kotatsuredo.server.filter

import java.text.Normalizer

/**
 * How text is folded before it meets the word lists.
 *
 * The useful distinction is not "aggressive versus conservative" but *which transforms*, because
 * they differ enormously in false-positive risk (PLAN.md §6). So there are exactly two sets, and the
 * dangerous one is confined to the tier where a miss is genuinely harmful.
 */
object FilterNormalizer {

	/**
	 * Set A - no meaningful false-positive risk, applied everywhere.
	 *
	 * NFKC, casefold, strip diacritics and combining marks, drop zero-width characters, fold the
	 * unambiguous Cyrillic and Greek homoglyphs to Latin, collapse three-or-more character repeats,
	 * remove punctuation appearing between letters, and apply the four substitutions that have no
	 * other reading.
	 */
	fun setA(raw: String, lang: String? = null): String =
		fold(raw, SET_A_SUBSTITUTIONS, keepMarks = lang in DIACRITIC_SENSITIVE)

	/**
	 * Set B - real collision risk, `severe` only.
	 *
	 * Adds the leetspeak substitutions whose letters genuinely collide with digits. `1` is `i` or
	 * `l`, `5` is `s`, `7` is `t`, `4` is `a`: each of those turns some ordinary strings into
	 * matches, which is why this is confined to a short, unambiguous term list and is *always* gated
	 * by the containing-word check.
	 */
	fun setB(raw: String): String = fold(raw, SET_B_SUBSTITUTIONS, keepMarks = false)

	/**
	 * Languages where stripping diacritics destroys the word.
	 *
	 * For French or Spanish a diacritic is decoration and folding `café` to `cafe` is free. For
	 * Vietnamese it is the word: `chó` (dog, and an insult) and `cho` (to give) are different words
	 * that the strip makes identical, and so are `tỉnh` (province) and `tinh`. Measured against
	 * ordinary Vietnamese prose, stripping put the false-positive rate at **31.6%** - one comment in
	 * three - because `các`, the plural marker and one of the commonest words in the language, folded
	 * onto a blocklist entry.
	 *
	 * Turkish is the same shape: `şık` means chic and folds onto a vulgarity.
	 *
	 * The cost is that `dit` typed without its marks will not match `địt`. That is evasion, which the
	 * plan accepts (§6); blocking a third of ordinary sentences is not.
	 */
	val DIACRITIC_SENSITIVE = setOf("vi", "tr", "pl")

	/**
	 * Splits on whitespace and hyphens.
	 *
	 * Other punctuation deliberately stays *inside* the token, because [setA] strips punctuation
	 * appearing between letters - which is the whole reason `f.u.c.k` folds to `fuck`. Splitting on
	 * `.` first would turn that into four one-letter tokens and throw the evasion away.
	 *
	 * Hyphens are the exception. A compound like `Lead-bismuth` folds into one string no dictionary
	 * will ever contain, so the containing-word check cannot rescue it and `smut` blocks a sentence
	 * about metallurgy. Splitting costs nothing: `f-u-c-k` becomes four one-character tokens, and the
	 * glue window puts them back together.
	 *
	 * Tokens are what the whole-token rule is stated in terms of, so this is the definition that
	 * decides whether `assassin` is one thing or three.
	 */
	fun tokenize(raw: String): List<String> = raw
		.split(*SEPARATORS)
		.filter { it.isNotBlank() }

	/**
	 * True for scripts that have no word boundaries.
	 *
	 * Japanese, Chinese, Korean and Thai write without spaces, so "is the match the whole token" is
	 * undefined and the Scunthorpe defence is simply unavailable there. Those languages fall back to
	 * substring matching with a longer minimum term length and lean on the allowlists instead -
	 * expect a higher false-positive rate and watch those four rejection rates specifically.
	 */
	fun isBoundaryless(lang: String?): Boolean = lang in BOUNDARYLESS

	/** The shortest a term may be before it is matched as a substring in a boundaryless script. */
	const val MIN_SUBSTRING_LENGTH = 2

	private fun fold(raw: String, substitutions: Map<Char, Char>, keepMarks: Boolean): String {
		// NFKC first: it is what turns full-width and circled letters into ordinary ones, which is a
		// whole family of evasion handled before anything else has to think about it.
		val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
		// Decomposed so the marks can be dropped one by one - or recomposed and left alone, for the
		// languages where they are part of the letter rather than an accent on it.
		val stripped = if (keepMarks) {
			Normalizer.normalize(normalized, Normalizer.Form.NFC)
		} else {
			Normalizer.normalize(normalized, Normalizer.Form.NFD)
		}

		// Homoglyphs are folded only in *mixed-script* text, because that is what the transform is
		// for: `fuсk` with a Cyrillic es is evasion, and `соска` is the Russian for "pacifier".
		// Folding unconditionally turns that second word into `cocka`, which the English list then
		// blocks as a near-miss for `cock` - a false positive invented entirely by the normaliser,
		// in a language whose users would never find out why.
		val foldHomoglyphs = stripped.any { it in 'a'..'z' }

		val builder = StringBuilder(stripped.length)
		for (character in stripped) {
			when {
				// Combining marks: this is the diacritic strip, and it also removes the zalgo case.
				!keepMarks && character.isMark() -> Unit
				character in ZERO_WIDTH -> Unit
				else -> {
					val substituted = substitutions[character]
						?: HOMOGLYPHS[character]?.takeIf { foldHomoglyphs }
						?: character
					// Punctuation *between* letters of a candidate match is dropped, so `f.u.c.k`
					// folds to `fuck`. Punctuation between words is a separator and is preserved by
					// tokenize, which runs on the raw text.
					if (substituted.isLetterOrDigit()) builder.append(substituted)
				}
			}
		}
		return collapseRepeats(builder.toString())
	}

	/**
	 * `fuuuck` becomes `fuck`: a run of three or more collapses to a single character.
	 *
	 * Three or more, not two, because English is full of ordinary doubled letters - `bass` has to
	 * survive as `bass`, or the lists stop meaning what they say. A run of exactly two is left alone
	 * for that reason, and only longer runs are treated as emphasis.
	 */
	private fun collapseRepeats(value: String): String {
		if (value.length < REPEAT_THRESHOLD) return value
		val builder = StringBuilder(value.length)
		var index = 0
		while (index < value.length) {
			val character = value[index]
			var run = 1
			while (index + run < value.length && value[index + run] == character) run++
			// A long run says nothing except "I am shouting", so it folds to one. A double is spelling.
			repeat(if (run >= REPEAT_THRESHOLD) 1 else run) { builder.append(character) }
			index += run
		}
		return builder.toString()
	}

	private fun Char.isMark(): Boolean = when (Character.getType(this).toByte()) {
		Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
		else -> false
	}

	private const val REPEAT_THRESHOLD = 3

	private val ZERO_WIDTH = setOf('​', '‌', '‍', '﻿', '⁠', '­')

	/** Unambiguous only: characters that are visually identical and have no other reading. */
	private val HOMOGLYPHS = mapOf(
		'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x',
		'к' to 'k', 'м' to 'm', 'т' to 't', 'в' to 'b', 'н' to 'h',
		'ο' to 'o', 'ε' to 'e', 'α' to 'a', 'ρ' to 'p', 'τ' to 't', 'ν' to 'v', 'κ' to 'k',
	)

	private val SET_A_SUBSTITUTIONS = mapOf('@' to 'a', '$' to 's', '0' to 'o', '3' to 'e')

	private val SET_B_SUBSTITUTIONS = SET_A_SUBSTITUTIONS + mapOf(
		'1' to 'i', '5' to 's', '7' to 't', '4' to 'a', '!' to 'i',
	)

	private val BOUNDARYLESS = setOf("ja", "zh", "ko", "th")

	private val SEPARATORS: Array<String> =
		arrayOf(" ", "\t", "\n", "\r", "\u000b", "\u3000", "\u00a0", "\u2007", "\u202f", "-", "\u2013", "\u2014")
}
