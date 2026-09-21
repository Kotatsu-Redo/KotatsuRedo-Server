package io.kotatsuredo.server.moderation

import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.comments.CommentState
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.sha256
import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.works.WorkRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Base64

private val log = LoggerFactory.getLogger("ModerationService")

/**
 * The moderation system.
 *
 * Every state-changing method here writes to `mod_action` before it returns, without exception. That
 * is not bookkeeping: with several people holding the credential and no report button to point at,
 * the audit log is what makes "who removed this, and why" answerable and a disagreement between
 * moderators resolvable (PLAN.md §6).
 */
class ModerationService(
	private val moderators: ModeratorRepository,
	private val queues: ModerationQueueRepository,
	private val comments: CommentRepository,
	private val identities: IdentityRepository,
	private val works: WorkRepository,
	private val ratings: RatingService,
	private val clock: Clock = Clock.systemUTC(),
	private val issuer: String = "Kotatsu-Redo",
) {

	private val random = SecureRandom()
	private val tokenEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

	private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

	// -- accounts and sessions -------------------------------------------------------------------

	/**
	 * Creates the first admin, once, from configuration.
	 *
	 * Disables itself the moment any moderator exists, so the env var cannot be left in place as a
	 * standing back door. The account still has to enrol in TOTP before it can do anything.
	 */
	fun bootstrapFirstAdmin(username: String, password: String): Moderator? {
		if (moderators.count() > 0) return null
		require(password.length in Passwords.MIN_LENGTH..Passwords.MAX_LENGTH) {
			"bootstrap password must be between ${Passwords.MIN_LENGTH} and ${Passwords.MAX_LENGTH} characters"
		}
		val created = moderators.create(
			id = newId(),
			username = username,
			passwordHash = Passwords.hash(password),
			role = ModeratorRole.ADMIN,
			invitedBy = null,
		)
		if (created != null) {
			log.warn(
				"Bootstrapped the first admin account '{}'. Remove MOD_BOOTSTRAP_* from the " +
					"environment and enrol TOTP before using it.",
				username,
			)
		}
		return created
	}

	fun login(username: String, password: String, code: String?): LoginResult {
		val moderator = moderators.findByUsername(username)
		val storedHash = moderator?.let { moderators.passwordHashOf(it.id) }
		if (!Passwords.verifyOrDummy(password, storedHash) || moderator == null) {
			// Same answer for an unknown username and a wrong password, so the endpoint cannot be
			// used to enumerate who has an account.
			return LoginResult.InvalidCredentials
		}
		if (moderator.isDisabled) return LoginResult.Disabled

		if (moderator.totpConfirmed) {
			val secret = moderators.totpSecretOf(moderator.id) ?: return LoginResult.TotpRequired
			val step = code?.let { Totp.verify(secret, it, clock.instant().epochSecond) }
				?: return LoginResult.TotpRequired
			// Burning the step is what stops the same code being replayed inside its 30-second life.
			if (!moderators.burnTotpStep(moderator.id, step)) return LoginResult.TotpRequired
		}

		val token = newToken()
		val expiresAt = now().plusHours(ModerationRules.SESSION_HOURS)
		moderators.openSession(
			sha256(token.toByteArray(Charsets.UTF_8)),
			moderator.id,
			expiresAt,
			mfaVerified = moderator.totpConfirmed,
		)
		moderators.touchLogin(moderator.id, now())
		return LoginResult.Ok(token, ModSession(moderator, expiresAt, mfaVerified = moderator.totpConfirmed))
	}

	fun authenticate(token: String): ModSession? =
		moderators.findSession(sha256(token.toByteArray(Charsets.UTF_8)), now())

	fun logout(token: String) = moderators.closeSession(sha256(token.toByteArray(Charsets.UTF_8)))

	/**
	 * Starts TOTP enrolment. The secret is stored unconfirmed, so an interrupted enrolment leaves the
	 * account exactly where it was rather than locked out.
	 */
	fun startEnrolment(moderator: Moderator, token: String): EnrolResult {
		if (moderator.totpConfirmed) return EnrolResult.AlreadyEnrolled
		val secret = moderators.startTotpEnrolment(
			moderator.id,
			sha256(token.toByteArray(Charsets.UTF_8)),
			Totp.generateSecret(),
		) ?: return EnrolResult.InProgress
		return EnrolResult.Started(secret, Totp.provisioningUri(secret, moderator.username, issuer))
	}

	fun confirmEnrolment(moderator: Moderator, token: String, code: String): Boolean {
		if (moderator.totpConfirmed) return false
		val secret = moderators.totpSecretOf(moderator.id) ?: return false
		val step = Totp.verify(secret, code, clock.instant().epochSecond) ?: return false
		return moderators.confirmTotpAndPromoteSession(
			moderator.id,
			sha256(token.toByteArray(Charsets.UTF_8)),
			secret,
			step,
		)
	}

	fun invite(actor: Moderator, username: String, password: String, role: ModeratorRole): Moderator? {
		val trimmed = username.trim()
		if (trimmed.length !in ModerationRules.MIN_USERNAME_LENGTH..ModerationRules.MAX_USERNAME_LENGTH) {
			return null
		}
		if (password.length !in Passwords.MIN_LENGTH..Passwords.MAX_LENGTH) return null

		val created = moderators.create(newId(), trimmed, Passwords.hash(password), role, actor.id)
			?: return null
		record(actor, ModActions.INVITE_MODERATOR, ModActions.TARGET_MODERATOR, created.id, null) {
			put("username", trimmed)
			put("role", role.name)
		}
		return created
	}

	/**
	 * @return false when the change would leave no enabled admin - the one edit that cannot be undone
	 *  from inside the panel.
	 */
	fun updateModerator(actor: Moderator, id: String, role: ModeratorRole?, disabled: Boolean?): Boolean {
		val target = moderators.find(id) ?: return false
		if (!moderators.updateAccountSafely(id, role, disabled)) return false
		record(actor, ModActions.UPDATE_MODERATOR, ModActions.TARGET_MODERATOR, id, null) {
			put("username", target.username)
			role?.let { put("role", it.name) }
			disabled?.let { put("disabled", it) }
		}
		return true
	}

	/** Rotates a moderator credential after proving both factors, and invalidates every session. */
	fun changePassword(
		actor: Moderator,
		currentPassword: String,
		newPassword: String,
		code: String,
	): PasswordChangeResult {
		if (newPassword.length !in Passwords.MIN_LENGTH..Passwords.MAX_LENGTH) {
			return PasswordChangeResult.INVALID_NEW_PASSWORD
		}
		val storedHash = moderators.passwordHashOf(actor.id)
		if (currentPassword.length > Passwords.MAX_LENGTH ||
			storedHash == null || !Passwords.verify(currentPassword, storedHash)
		) {
			return PasswordChangeResult.INVALID_CURRENT_PASSWORD
		}
		val secret = moderators.totpSecretOf(actor.id) ?: return PasswordChangeResult.INVALID_TOTP
		val step = Totp.verify(secret, code, clock.instant().epochSecond)
			?: return PasswordChangeResult.INVALID_TOTP
		if (!moderators.burnTotpStep(actor.id, step)) return PasswordChangeResult.INVALID_TOTP
		if (!moderators.changePasswordAndCloseSessions(actor.id, Passwords.hash(newPassword))) {
			return PasswordChangeResult.INVALID_CURRENT_PASSWORD
		}
		record(actor, ModActions.CHANGE_PASSWORD, ModActions.TARGET_MODERATOR, actor.id, null) {}
		return PasswordChangeResult.CHANGED
	}

	/** For a moderator who lost their authenticator. Clears the secret so they enrol again. */
	fun resetTotp(actor: Moderator, id: String): Boolean = moderators.resetTotpWithAudit(actor.id, id)

	fun listModerators(): List<Moderator> = moderators.all()

	// -- comments --------------------------------------------------------------------------------

	/**
	 * Removes a comment and snapshots its text into the audit log.
	 *
	 * The snapshot is not optional: removal blanks the body in place, so without it the log would
	 * record that something was removed and lose what it said - which is precisely the question the
	 * log exists to answer, and the only way [restoreComment] can put it back.
	 */
	fun removeComment(actor: Moderator, commentId: Long, reason: String): Boolean {
		val existing = comments.find(commentId) ?: return false
		if (existing.state == CommentState.REMOVED) return false

		val detail = buildJsonObject {
			put("body", existing.body)
			put("author", existing.userId)
			put("work_id", existing.workId)
			put("lang", existing.lang)
			put("previous_state", existing.state.name)
		}.toString()
		return moderators.removeCommentWithAudit(actor.id, commentId, reason, detail)
	}

	/**
	 * "Looked at it, it stays."
	 *
	 * The comment is untouched and its author is told nothing - the only thing that changes is that
	 * the disliked and flagged queues stop offering it. Without this the queues can only be emptied
	 * by removing comments that did not deserve removing, so they fill with things nobody can clear
	 * and stop being read.
	 */
	fun dismissComment(actor: Moderator, commentId: Long, reason: String?): Boolean {
		val existing = comments.find(commentId) ?: return false
		return moderators.dismissCommentWithAudit(actor.id, commentId, reason, existing.flaggedRule)
	}

	/**
	 * Puts a removed comment back, restoring its text from the audit snapshot.
	 *
	 * A comment its own author deleted has no snapshot and so cannot be restored - deliberately. A
	 * moderator undoing a user's own deletion would be republishing words that person withdrew.
	 */
	fun restoreComment(actor: Moderator, commentId: Long, reason: String?): Boolean {
		val existing = comments.find(commentId) ?: return false
		if (existing.state != CommentState.REMOVED) return false

		val snapshot = moderators.lastRemovalDetail(commentId.toString()) ?: return false
		val parsed = runCatching { Json.parseToJsonElement(snapshot).jsonObject }.getOrNull() ?: return false
		val body = parsed["body"]?.jsonPrimitive?.contentOrNull()?.takeIf { it.isNotBlank() } ?: return false
		val previous = parsed["previous_state"]?.jsonPrimitive?.contentOrNull()
			?.let { name -> CommentState.entries.firstOrNull { it.name == name } }
			?: CommentState.VISIBLE

		val detail = buildJsonObject { put("restored_state", previous.name) }.toString()
		return moderators.restoreCommentWithAudit(
			actor.id, commentId, body, previous.code, reason, detail,
		)
	}

	// -- users -----------------------------------------------------------------------------------

	/**
	 * Bans a user and clears their comments, which is what the rules page promises (PLAN.md §6).
	 *
	 * A ban also removes read access, because [requireCaller] rejects a banned user on every endpoint.
	 * That is the deliberate consequence of identity being all-or-nothing, and it is why this action
	 * needs a reason and shows up in the log with a name against it.
	 */
	fun banUser(actor: Moderator, userId: String, reason: String): Boolean {
		return moderators.banUserWithAudit(actor.id, userId, reason)
	}

	/** Comments are not restored: the ban deleted them, and a reversal does not un-delete words. */
	fun unbanUser(actor: Moderator, userId: String, reason: String?): Boolean {
		return moderators.unbanUserWithAudit(actor.id, userId, reason)
	}

	fun setShadowban(actor: Moderator, userId: String, shadowbanned: Boolean, reason: String?): Boolean {
		val action = if (shadowbanned) ModActions.SHADOWBAN_USER else ModActions.UNSHADOWBAN_USER
		return moderators.mutateWithAudit(
			actor.id,
			action,
			ModActions.TARGET_USER,
			userId,
			reason,
			detail = { rebuilt -> buildJsonObject { put("comment_scores_rebuilt", rebuilt) }.toString() },
		) { connection ->
			if (!identities.setShadowbanned(connection, userId, shadowbanned)) return@mutateWithAudit null
			val affectedVotes = comments.votedCommentIds(connection, userId)
			comments.recountVotes(connection, affectedVotes)
			affectedVotes.size
		} != null
	}

	fun resetNickname(actor: Moderator, userId: String, reason: String?): Boolean {
		val user = identities.findById(userId) ?: return false
		return moderators.resetNicknameWithAudit(actor.id, userId, reason, user.nickname)
	}

	// -- devices ---------------------------------------------------------------------------------

	/**
	 * Bans the device this user signed up from.
	 *
	 * Only the ANDROID_ID hash is enforced. The DRM hash is stored alongside it so a later signup can
	 * be *flagged*, never blocked: those ids collide across devices from the same manufacturer, and
	 * blocking on one would ban a stranger (PLAN.md §1).
	 */
	fun banDevice(actor: Moderator, userId: String, reason: String?): Boolean {
		val (ssaid, drm) = identities.deviceOf(userId) ?: return false
		return moderators.mutateWithAudit(
			actor.id,
			ModActions.BAN_DEVICE,
			ModActions.TARGET_DEVICE,
			fingerprint(ssaid),
			reason,
			detail = {
				buildJsonObject {
					put("user_id", userId)
					put("has_drm_id", drm != null)
				}.toString()
			},
		) { connection ->
			if (identities.banDevice(connection, ssaid, drm, actor.id, reason, now())) true else null
		} != null
	}

	/** Admin only. There is no in-app appeal surface by design, so reversal lives here and only here. */
	fun unbanDevice(actor: Moderator, fingerprint: String): Boolean {
		val match = identities.bannedDevices(MAX_DEVICE_BANS).firstOrNull { it.fingerprint == fingerprint }
			?: return false
		return moderators.mutateWithAudit(
			actor.id,
			ModActions.UNBAN_DEVICE,
			ModActions.TARGET_DEVICE,
			fingerprint,
			null,
			detail = { "{}" },
		) { connection ->
			if (identities.unbanDevice(connection, match.ssaidHash)) true else null
		} != null
	}

	fun bannedDevices(): List<io.kotatsuredo.server.identity.BannedDevice> =
		identities.bannedDevices(MAX_DEVICE_BANS)

	/**
	 * Everyone currently under sanction.
	 *
	 * Neither sanction is visible anywhere else: a ban shows up only as an absence, and a shadowban is
	 * invisible by design - including to the person it was applied to. Without this, "who have we
	 * acted against" means reading the audit log line by line, and lifting one has nowhere to be done
	 * from.
	 */
	fun sanctionedUsers(): List<SanctionedUser> = queues.sanctioned(MAX_SANCTIONS)

	// -- works -----------------------------------------------------------------------------------

	fun mergeWorks(actor: Moderator, from: Long, into: Long, reason: String): Boolean {
		if (from == into) return false
		if (works.metadataOf(from) == null || works.metadataOf(into) == null) return false

		works.mergeWorks(from = from, into = into, reason = reason, moderatorId = actor.id)
		// Ratings moved, so both aggregates are now wrong until recomputed. The details screen reads
		// the aggregate, not the ratings, so skipping this shows stale numbers indefinitely.
		ratings.recompute(into)
		ratings.recompute(from)
		record(actor, ModActions.MERGE_WORKS, ModActions.TARGET_WORK, from.toString(), reason) {
			put("into", into)
		}
		return true
	}

	/**
	 * Hands a batch of never-described works to the enricher, which fills in their titles and ids and
	 * merges away the ones that turn out to be duplicates of something we already had.
	 *
	 * Deliberately a button rather than a background sweep: it is thousands of requests to somebody
	 * else's free API, and that is a decision a person makes, not something that starts on its own.
	 *
	 * @return how many were queued
	 */
	fun backfillCatalogue(actor: Moderator, limit: Int): Int {
		val queued = works.enqueueBackfill(limit)
		if (queued > 0) {
			record(actor, ModActions.BACKFILL_CATALOGUE, ModActions.TARGET_SYSTEM, "catalogue", null) {
				put("queued", queued)
			}
		}
		return queued
	}

	fun unmergeWorks(actor: Moderator, from: Long, reason: String?): Boolean {
		val (restored, into) = works.unmergeWork(from) ?: return false
		ratings.recompute(restored)
		ratings.recompute(into)
		record(actor, ModActions.UNMERGE_WORKS, ModActions.TARGET_WORK, from.toString(), reason) {
			put("was_merged_into", into)
		}
		return true
	}

	// -- flags -----------------------------------------------------------------------------------

	fun reviewBanEvasion(actor: Moderator, flagId: Long, note: String?): Boolean {
		return moderators.mutateWithAudit(
			actor.id,
			ModActions.REVIEW_FLAG,
			ModActions.TARGET_FLAG,
			"ban_evasion:$flagId",
			note,
			detail = { "{}" },
		) { connection ->
			if (identities.reviewBanEvasionFlag(connection, flagId, now())) true else null
		} != null
	}

	fun reviewBrigade(actor: Moderator, flagId: Long, note: String?): Boolean {
		return moderators.mutateWithAudit(
			actor.id,
			ModActions.REVIEW_FLAG,
			ModActions.TARGET_FLAG,
			"brigade:$flagId",
			note,
			detail = { "{}" },
		) { connection ->
			if (queues.reviewBrigadeFlag(connection, flagId)) true else null
		} != null
	}

	fun resolveDispute(actor: Moderator, disputeId: Long, note: String?): Boolean {
		return moderators.mutateWithAudit(
			actor.id,
			ModActions.RESOLVE_DISPUTE,
			ModActions.TARGET_DISPUTE,
			disputeId.toString(),
			note,
			detail = { "{}" },
		) { connection ->
			if (queues.resolveDispute(connection, disputeId, actor.id)) true else null
		} != null
	}

	/**
	 * Records a filter edit.
	 *
	 * Filter changes belong in the same log as removals and bans: allowlisting a term changes what
	 * every user is allowed to say, which is a moderation decision like any other and needs a name
	 * against it.
	 */
	fun recordFilterAction(actor: Moderator, action: String, term: String, detail: String?) {
		record(actor, action, ModActions.TARGET_FILTER, term, null) {
			detail?.let { put("detail", it) }
		}
	}

	// -- audit -----------------------------------------------------------------------------------

	/** An admin sees everything; a moderator sees their own actions and nobody else's. */
	fun actions(actor: Moderator, before: Long?, limit: Int): List<ModActionRecord> =
		moderators.actions(
			moderatorId = if (actor.role.isAdmin) null else actor.id,
			before = before,
			limit = limit.coerceIn(1, ModerationRules.MAX_PAGE_SIZE),
		)

	/** Housekeeping for the session and TOTP tables. Cheap, and nothing depends on it being timely. */
	fun sweep() {
		moderators.purgeExpiredSessions(now())
		moderators.purgeOldTotpSteps(Totp.timeStep(clock.instant().epochSecond) - TOTP_HISTORY_STEPS)
	}

	private fun record(
		actor: Moderator,
		action: String,
		targetType: String,
		targetId: String,
		reason: String?,
		detail: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
	) {
		moderators.record(actor.id, action, targetType, targetId, reason, buildJsonObject(detail).toString())
	}

	private fun newId(): String = tokenEncoder.encodeToString(ByteArray(12).also(random::nextBytes))

	private fun newToken(): String = tokenEncoder.encodeToString(ByteArray(32).also(random::nextBytes))

	private fun fingerprint(hash: ByteArray): String =
		hash.joinToString("") { "%02x".format(it) }.take(FINGERPRINT_LENGTH)

	private companion object {
		/** Enough to list every ban a small instance will ever have, and a bound if that is wrong. */
		const val MAX_DEVICE_BANS = 1000
		const val MAX_SANCTIONS = 500
		const val FINGERPRINT_LENGTH = 16

		/** Roughly a day of used TOTP steps. Older ones cannot be replayed anyway. */
		const val TOTP_HISTORY_STEPS = 2880L
	}
}

private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null
