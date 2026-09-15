package io.kotatsuredo.server.filter

import java.time.OffsetDateTime

/**
 * The posture is strict: ordinary swearing is blocked, not merely flagged. A reader can say a chapter
 * was disappointing without swearing at it, and the goal is a thread about the manga rather than a
 * fight (PLAN.md §6).
 */
enum class FilterTier(val code: Short) {
	/**
	 * Slurs, identity attacks, sexualised minors. Blocked, matched as a substring with set B, and
	 * **global** - the only tier that applies in every language, because a miss here is genuinely
	 * harmful and the term list is short and unambiguous.
	 */
	SEVERE(0),

	/** Ordinary swearing. Blocked, whole-token matching with set A, scoped to the comment's language. */
	PROFANITY(1),

	/** Borderline and drama-adjacent. Publishes, flags, and goes to the top of the panel's queue. */
	WATCH(2),
	;

	val blocks: Boolean get() = this != WATCH

	companion object {
		fun of(code: Short): FilterTier = entries.firstOrNull { it.code == code } ?: PROFANITY

		fun parse(name: String?): FilterTier? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
	}
}

data class FilterRule(
	val id: Long,
	/** Already normalised with the tier's set, so it meets the folded text in the same space. */
	val term: String,
	val termRaw: String,
	val tier: FilterTier,
	/** Null means every language. Only [FilterTier.SEVERE] is ever global. */
	val lang: String?,
	val isEnabled: Boolean,
	val demotedAt: OffsetDateTime?,
	val source: String,
)

/** A logged block, as the panel sees it. */
data class FilterBlockRecord(
	val id: Long,
	val ruleId: Long?,
	val term: String,
	val tier: FilterTier,
	val lang: String?,
	val surface: String,
	val userId: String?,
	val userName: String?,
	val context: String?,
	val createdAt: OffsetDateTime,
	val disputedAt: OffsetDateTime?,
	val reviewedAt: OffsetDateTime?,
	val resolution: String?,
)

/**
 * How often a rule is wrong.
 *
 * The number that decides whether the list feels strict or feels broken, and the reason every block
 * is logged: without it nobody ever discovers that the `pt` list is bad.
 */
data class RuleStats(
	val ruleId: Long?,
	val term: String,
	val tier: FilterTier,
	val lang: String?,
	val blocks: Int,
	val disputed: Int,
) {
	val falsePositiveRate: Double get() = if (blocks == 0) 0.0 else disputed.toDouble() / blocks
}

data class LanguageStats(val lang: String?, val blocks: Int, val disputed: Int)

object FilterRules {

	/**
	 * A rule that misfires this often stops blocking on its own rather than waiting for someone to
	 * notice. Paired with a minimum independent settled-account sample, because one reporter is noise.
	 */
	const val AUTO_DEMOTE_RATE = 0.30
	const val AUTO_DEMOTE_MIN_REPORTERS = 5

	/**
	 * How long the blocked text itself is kept.
	 *
	 * Long enough for a moderator to work the queue, short enough that this is not a permanent
	 * archive of everything anyone was stopped from saying. The row survives; only the text goes.
	 */
	const val CONTEXT_RETENTION_DAYS = 30L

	/**
	 * How much of the comment is kept as context.
	 *
	 * Enough to judge whether the block was right, not the whole essay.
	 */
	const val CONTEXT_LENGTH = 500

	const val SURFACE_COMMENT = "comment"
	const val SURFACE_NICKNAME = "nickname"

	const val RESOLUTION_UPHELD = "upheld"
	const val RESOLUTION_ALLOWLISTED = "allowlisted"
	const val RESOLUTION_DEMOTED = "demoted"

	/**
	 * The most tokens that will be glued together looking for separator evasion, and the longest a
	 * token may be to take part.
	 *
	 * Separator evasion looks like `f u c k` - single letters. Bounding the window to short tokens
	 * keeps the work trivial and, more importantly, keeps `the rap ist` in scope so the dictionary
	 * can rescue `therapist`.
	 */
	/**
	 * The shortest a rule may be *after normalisation*.
	 *
	 * Checked on the folded form, not the typed one, because folding can shorten a term past the
	 * point of safety: `xxx` collapses to `x` under the repeat rule, and a one-character rule in a
	 * substring-matched tier blocks essentially every comment. The seed loader and the panel both
	 * enforce this, because a rule that arrives by either path is equally capable of it.
	 */
	const val MIN_TERM_LENGTH = 2

	const val MAX_GLUE_TOKENS = 4

	/**
	 * Raised from three to six so that phrase terms work at all.
	 *
	 * Most of the Vietnamese vocabulary is two syllables, and Vietnamese syllables carry diacritics
	 * that push them past three characters - `địt mẹ` is 3 + 2 only after normalisation strips the
	 * marks. A joined phrase is stored as one term and assembled by this window, so a limit that
	 * cannot span an ordinary two-word phrase silently drops a whole language.
	 *
	 * Safe to raise because a window only counts when the term is the *whole* window: two ordinary
	 * words happening to concatenate into exactly a listed term is a far narrower accident than the
	 * substring matching this replaced.
	 */
	const val MAX_GLUE_TOKEN_LENGTH = 6
}
