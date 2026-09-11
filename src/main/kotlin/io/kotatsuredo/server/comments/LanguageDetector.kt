package io.kotatsuredo.server.comments

/**
 * Decides what language a comment is written in.
 *
 * Detected from the text rather than taken from the client's locale, because a French-locale user
 * writing in English would otherwise have the French list applied and the English one skipped -
 * both a false-positive source and a trivial filter bypass (PLAN.md §6). This is what selects the
 * profanity list, so it is part of the filter rather than a display nicety, and it arrives with it:
 * until then [ClientHint] uses the app's locale, which is the documented low-confidence fallback.
 */
fun interface LanguageDetector {

	/** @param fallback the client's UI language, already reduced to a bare subtag. */
	fun detect(body: String, fallback: String?): String?

	companion object {
		val ClientHint = LanguageDetector { _, fallback -> fallback }
	}
}
