package io.kotatsuredo.server.comments

import java.time.OffsetDateTime

enum class CommentState(val code: Short) {
	VISIBLE(0),

	/**
	 * Renders normally for its author and is invisible to everyone else, and their votes stop
	 * counting. Nothing in any response may reveal it - a shadowban that announces itself is just a
	 * ban with extra steps.
	 */
	SHADOWED(1),
	REMOVED(2),
	;

	companion object {
		fun of(code: Short): CommentState = entries.firstOrNull { it.code == code } ?: VISIBLE
	}
}

data class Comment(
	val id: Long,
	val workId: Long,
	val chapterId: Long?,
	/** Null once the author deleted their account; the row survives only as a tombstone. */
	val userId: String?,
	val parentId: Long?,
	val depth: Int,
	val body: String,
	val isSpoiler: Boolean,
	val lang: String?,
	val createdAt: OffsetDateTime,
	val editedAt: OffsetDateTime?,
	val state: CommentState,
	val score: Double,
	val up: Int,
	val down: Int,
	/** The `watch` term that flagged this, for the panel. Never sent to a reader. */
	val flaggedRule: String? = null,
)

/** A comment plus what this particular caller is allowed to know about it. */
data class CommentView(
	val comment: Comment,
	val authorName: String,
	val myVote: Int,
	val isMine: Boolean,
)

sealed interface PostResult {
	data class Posted(val view: CommentView) : PostResult

	data class TooShort(val minimum: Int) : PostResult

	data object TooLong : PostResult

	/** A term matched the word filter. [term] is the user's own word, echoed back untranslated. */
	data class Blocked(val term: String, val tier: String, val blockId: Long? = null) : PostResult

	/** Two people have already exchanged their allowance of replies in this chain. */
	data object ChainDepthExceeded : PostResult

	data object ParentNotFound : PostResult
}

sealed interface EditResult {
	data class Edited(val view: CommentView) : EditResult

	data class TooShort(val minimum: Int) : EditResult

	data object TooLong : EditResult

	data class Blocked(val term: String, val tier: String, val blockId: Long? = null) : EditResult

	/** Past the edit window, someone else's comment, or already removed. All indistinguishable. */
	data object NotEditable : EditResult

	data object NotFound : EditResult
}

/** What a reply notification carries: the reply itself plus enough to open the right thread. */
data class ReplyNotification(
	val comment: Comment,
	val authorName: String,
)

object CommentRules {

	/**
	 * Removes "first", "up", "lol" without making any judgement about content.
	 *
	 * Twenty was chosen to sit where n-gram language detection becomes reliable, which it no longer
	 * does: [io.kotatsuredo.server.filter.StopwordLanguageDetector] needs 12 characters, so a comment
	 * between 10 and 12 falls back to the work's language rather than being detected. That costs a
	 * little filter precision on very short comments and buys back every legitimate one-line reply.
	 */
	/**
	 * The default. The running value comes from `COMMENT_MIN_LENGTH` - see [CommentService.minLength] -
	 * so tuning it is an edit to `.env` and a restart rather than a new build and a new app release.
	 */
	const val MIN_BODY_LENGTH = 10

	const val MAX_BODY_LENGTH = 4000

	/**
	 * How many comments one person may add to an unbroken two-person chain.
	 *
	 * Caps the duel, not the discussion: a third participant joining is conversation rather than a
	 * fight, and is not limited by this.
	 */
	const val MAX_CHAIN_ROUNDS = 3

	/** Long enough to fix a typo, short enough that votes have not accrued against the old text. */
	const val EDIT_WINDOW_MINUTES = 5L

	/**
	 * How far back `GET /notifications` will look for a client that has been away.
	 *
	 * Notifications are delivered once and the cursor lives on the device, so a client that lost its
	 * cursor would otherwise ask for everything since the beginning of time.
	 */
	const val NOTIFICATION_LOOKBACK_DAYS = 30L

	const val MAX_PAGE_SIZE = 50
	const val DEFAULT_PAGE_SIZE = 25

	/**
	 * Counts what a person would call characters, so an emoji or an accented letter is one, and a
	 * twenty-emoji comment is not rejected as "too short" while a twenty-letter one passes.
	 */
	fun length(body: String): Int = body.codePointCount(0, body.length)
}

/** Where a filtered piece of text came from. Recorded on every block so the log can be sliced by it. */
object FilterSurface {
	const val COMMENT = "comment"
	const val REPLY = "reply"
	const val NICKNAME = "nickname"
}
