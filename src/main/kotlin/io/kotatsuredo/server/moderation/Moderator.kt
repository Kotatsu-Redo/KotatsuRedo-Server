package io.kotatsuredo.server.moderation

import java.time.OffsetDateTime

enum class ModeratorRole(val code: Short) {
	/** Acts on comments and users. The day-to-day role, and the one most accounts should have. */
	MODERATOR(0),

	/** Also manages moderators, merges and unmerges works, reverses device bans, sees the full log. */
	ADMIN(1),
	;

	val isAdmin: Boolean get() = this == ADMIN

	companion object {
		fun of(code: Short): ModeratorRole = entries.firstOrNull { it.code == code } ?: MODERATOR

		fun parse(name: String?): ModeratorRole? =
			entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
	}
}

/** Never carries the password hash or the TOTP secret - those leave the repository nowhere. */
data class Moderator(
	val id: String,
	val username: String,
	val role: ModeratorRole,
	val isDisabled: Boolean,
	val totpConfirmed: Boolean,
	val createdAt: OffsetDateTime,
	val lastLoginAt: OffsetDateTime?,
	val invitedBy: String?,
)

/**
 * A moderator's session.
 *
 * [needsTotpEnrolment] is what makes "TOTP from the start" real: an account that has not enrolled
 * gets a session that can do nothing except enrol.
 */
data class ModSession(
	val moderator: Moderator,
	val expiresAt: OffsetDateTime,
) {
	val needsTotpEnrolment: Boolean get() = !moderator.totpConfirmed
}

sealed interface LoginResult {
	data class Ok(val token: String, val session: ModSession) : LoginResult

	/** Correct password, but the account has TOTP and the code was missing or wrong. */
	data object TotpRequired : LoginResult

	/** Wrong username or wrong password - deliberately the same answer for both. */
	data object InvalidCredentials : LoginResult

	data object Disabled : LoginResult
}

sealed interface EnrolResult {
	data class Started(val secret: String, val uri: String) : EnrolResult

	/** Already enrolled. Re-enrolling is an admin action on someone else's account, not a self-serve one. */
	data object AlreadyEnrolled : EnrolResult
}

/**
 * One row of the audit log.
 *
 * [detail] is a JSON snapshot of what changed - for a removed comment, the body, which the comment
 * row itself no longer holds once it is blanked.
 */
data class ModActionRecord(
	val id: Long,
	val moderatorId: String,
	val moderatorName: String,
	val action: String,
	val targetType: String,
	val targetId: String,
	val reason: String?,
	val detail: String?,
	val createdAt: OffsetDateTime,
)

/** The vocabulary of the audit log. String-valued in the database so old rows stay readable. */
object ModActions {
	const val REMOVE_COMMENT = "remove_comment"
	const val RESTORE_COMMENT = "restore_comment"
	const val BAN_USER = "ban_user"
	const val UNBAN_USER = "unban_user"
	const val SHADOWBAN_USER = "shadowban_user"
	const val UNSHADOWBAN_USER = "unshadowban_user"
	const val RESET_NICKNAME = "reset_nickname"
	const val BAN_DEVICE = "ban_device"
	const val UNBAN_DEVICE = "unban_device"
	const val MERGE_WORKS = "merge_works"
	const val UNMERGE_WORKS = "unmerge_works"
	const val REVIEW_FLAG = "review_flag"
	const val RESOLVE_DISPUTE = "resolve_dispute"
	const val INVITE_MODERATOR = "invite_moderator"
	const val UPDATE_MODERATOR = "update_moderator"
	const val RESET_TOTP = "reset_totp"

	const val TARGET_COMMENT = "comment"
	const val TARGET_USER = "user"
	const val TARGET_DEVICE = "device"
	const val TARGET_WORK = "work"
	const val TARGET_FLAG = "flag"
	const val TARGET_DISPUTE = "dispute"
	const val TARGET_MODERATOR = "moderator"
	const val TARGET_FILTER = "filter"
}

object ModerationRules {
	/**
	 * Short enough that an unattended panel does not stay open all day, long enough not to interrupt
	 * a shift. Absolute, not sliding: a session that renews itself on activity never actually expires.
	 */
	const val SESSION_HOURS = 12L

	const val MIN_USERNAME_LENGTH = 3
	const val MAX_USERNAME_LENGTH = 32
	const val MAX_PAGE_SIZE = 100
	const val DEFAULT_PAGE_SIZE = 50

	/** Reasons are mandatory on anything that removes content or restricts a user. */
	const val MIN_REASON_LENGTH = 3
	const val MAX_REASON_LENGTH = 500
}
