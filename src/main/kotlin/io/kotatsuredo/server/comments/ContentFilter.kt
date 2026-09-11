package io.kotatsuredo.server.comments

/**
 * The word filter, as seen by the comment domain.
 *
 * Behind an interface because the comment domain has no business knowing about Aho-Corasick
 * automata, word lists or block logging - and because a deployment that has not curated its lists
 * should still be able to run with [PermitAll] rather than with a filter that blocks nothing but
 * pretends otherwise.
 */
fun interface ContentFilter {

	fun check(body: String, lang: String?, context: Context): Verdict

	/**
	 * Who is writing and where.
	 *
	 * Carried so the filter can log the block against a user and a surface: that log is the entire
	 * feedback loop that turns the word list from a guess made once into something tuned from real
	 * data (PLAN.md §6).
	 */
	data class Context(val userId: String?, val surface: String)

	sealed interface Verdict {
		data object Allowed : Verdict

		/**
		 * @param term the user's own word, echoed back untranslated so the app can name it in the
		 *  rejection - the user asked for the message to say which term was rejected (PLAN.md §6).
		 * @param tier which list matched, so the app can phrase "not allowed here" differently from
		 *  "never allowed".
		 * @param blockId the logged block, so the rejection dialog's "this was wrong" has something
		 *  to point at. Null when the filter does not keep a log.
		 */
		data class Blocked(val term: String, val tier: String, val blockId: Long? = null) : Verdict

		/**
		 * Borderline. Publishes normally and goes to the top of the panel's queue - the user is told
		 * nothing, because telling them would make this a block with extra steps.
		 */
		data class Flagged(val term: String, val tier: String) : Verdict
	}

	companion object {
		val PermitAll = ContentFilter { _, _, _ -> Verdict.Allowed }
	}
}
