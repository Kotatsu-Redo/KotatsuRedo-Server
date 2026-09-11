package io.kotatsuredo.server.filter

import io.kotatsuredo.server.comments.LanguageDetector

/**
 * Detects the language a comment is actually written in.
 *
 * This matters because the detected language is what selects the profanity list. Trusting the
 * client's locale instead is both a false-positive source and a trivial bypass: a French-locale user
 * writing English profanity would have the French list applied and the English one skipped
 * (PLAN.md §6).
 *
 * **Why not Lingua.** The plan named it, and it is the better detector. It also bundles n-gram models
 * for all seventy-five languages in a single ~100 MB artifact, which is a large thing to put on a
 * €7 box to choose between fifteen lists. What is here instead: script detection, which settles the
 * four boundaryless languages outright, plus stopword frequency for the Latin and Cyrillic ones,
 * which is reliable on the twenty-plus characters the minimum length guarantees. It is weaker than
 * Lingua on short mixed text and honest about it - [LanguageDetector] is a one-line swap if the
 * block log ever shows this choosing wrong.
 */
class StopwordLanguageDetector : LanguageDetector {

	override fun detect(body: String, fallback: String?): String? {
		val folded = FilterNormalizer.setA(body)
		if (folded.length < MIN_LENGTH) return fallback

		// Script first. A comment in kana or hangul is not ambiguous, and this is also what routes
		// the boundaryless languages to substring matching.
		byScript(body)?.let { return it }

		val tokens = FilterNormalizer.tokenize(body).map(FilterNormalizer::setA).filter { it.isNotEmpty() }
		if (tokens.size < MIN_TOKENS) return fallback

		val scores = STOPWORDS.mapValues { (_, words) -> tokens.count { it in words } }
		val best = scores.maxByOrNull { it.value } ?: return fallback
		if (best.value < MIN_HITS) return fallback

		// A clear winner or nothing. Two languages tied on stopwords means the text is too short or
		// too cognate to call, and guessing there is worse than falling back to what the client said.
		val runnerUp = scores.filterKeys { it != best.key }.values.maxOrNull() ?: 0
		return if (best.value > runnerUp) best.key else fallback
	}

	private fun byScript(body: String): String? {
		var han = 0
		var kana = 0
		var hangul = 0
		var thai = 0
		var cyrillic = 0
		var total = 0
		for (character in body) {
			if (!character.isLetter()) continue
			total++
			when (Character.UnicodeScript.of(character.code)) {
				Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
				Character.UnicodeScript.HAN -> han++
				Character.UnicodeScript.HANGUL -> hangul++
				Character.UnicodeScript.THAI -> thai++
				Character.UnicodeScript.CYRILLIC -> cyrillic++
				else -> Unit
			}
		}
		if (total == 0) return null
		val share = { count: Int -> count.toDouble() / total }

		return when {
			share(hangul) > SCRIPT_THRESHOLD -> "ko"
			share(thai) > SCRIPT_THRESHOLD -> "th"
			// Kana settles Japanese, and Japanese text is mostly han with kana particles through it -
			// so any meaningful kana share means Japanese rather than Chinese.
			kana > 0 && share(kana + han) > SCRIPT_THRESHOLD -> "ja"
			share(han) > SCRIPT_THRESHOLD -> "zh"
			share(cyrillic) > SCRIPT_THRESHOLD -> "ru"
			else -> null
		}
	}

	private companion object {
		const val MIN_LENGTH = 12
		const val MIN_TOKENS = 4
		const val MIN_HITS = 2
		const val SCRIPT_THRESHOLD = 0.30

		/**
		 * The commonest function words per language.
		 *
		 * Function words are the right signal because they are frequent, short and untranslated even
		 * in text full of loanwords - a Portuguese comment about a manga still says "de", "que", "não".
		 * Deliberately excludes words shared across the Romance languages where they would tie.
		 */
		val STOPWORDS: Map<String, Set<String>> = mapOf(
			"en" to setOf(
				"the", "and", "is", "was", "this", "that", "with", "have", "but", "they", "chapter",
				"it", "for", "you", "not", "are", "his", "her", "she", "just", "really", "about",
			),
			"es" to setOf(
				"el", "los", "las", "una", "pero", "porque", "muy", "este", "esta", "capitulo",
				"cuando", "tambien", "todo", "hay", "ser", "tiene", "mas", "asi",
			),
			"pt" to setOf(
				"nao", "uma", "com", "mas", "muito", "isso", "esse", "essa", "capitulo", "voce",
				"tambem", "sempre", "ainda", "sobre", "quando", "entao", "foi",
			),
			"fr" to setOf(
				"les", "des", "une", "est", "pas", "mais", "pour", "avec", "chapitre", "vraiment",
				"tout", "cette", "dans", "plus", "bien", "comme", "sur", "sont",
			),
			"de" to setOf(
				"der", "die", "das", "und", "ist", "nicht", "ein", "eine", "aber", "kapitel",
				"auch", "sich", "noch", "wie", "wird", "sehr", "mit", "war",
			),
			"it" to setOf(
				"che", "non", "per", "una", "sono", "come", "questo", "capitolo", "molto", "anche",
				"piu", "quando", "essere", "della", "nel", "ma",
			),
			"pl" to setOf(
				"nie", "sie", "jest", "tego", "tym", "ale", "jak", "bardzo", "rozdzial", "tylko",
				"juz", "byl", "jego", "przez", "czy", "wszystko",
			),
			"ru" to setOf(
				"i", "v", "ne", "chto", "na", "kak", "eto", "no", "glava", "ochen", "vse", "byl",
			),
			"tr" to setOf(
				"bir", "bu", "ve", "ama", "cok", "icin", "gibi", "daha", "boelum", "sonra",
				"kadar", "olarak", "ile", "her",
			),
			"id" to setOf(
				"yang", "dan", "ini", "itu", "tidak", "untuk", "dengan", "bab", "sangat", "sudah",
				"akan", "juga", "dari", "ada", "saya",
			),
			"vi" to setOf(
				"khong", "cua", "nhung", "duoc", "nguoi", "chuong", "rat", "minh", "cho", "mot",
				"nay", "trong", "voi", "cung",
			),
		)
	}
}
