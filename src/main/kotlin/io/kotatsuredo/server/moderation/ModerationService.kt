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
		require(password.length >= Passwords.MIN_LENGTH) {
			"bootstrap password must be at least ${Passwords.MIN_LENGTH} characters"
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
		if (moderator == null || storedHash == null || !Passwords.verify(password, storedHash)) {
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
		moderators.openSession(sha256(token.toByteArray(Charsets.UTF_8)), moderator.id, expiresAt)
		moderators.touchLogin(moderator.id, now())
		return LoginResult.Ok(token, ModSession(moderator, expiresAt))
	}

	fun authenticate(token: String): ModSession? =
		moderators.findSession(sha256(token.toByteArray(Charsets.UTF_8)), now())

	fun logout(token: String) = moderators.closeSession(sha256(token.toByteArray(Charsets.UTF_8)))

	/**
	 * Starts TOTP enrolment. The secret is stored unconfirmed, so an interrupted enrolment leaves the
	 * account exactly where it was rather than locked out.
	 */
	fun startEnrolment(moderator: Moderator): EnrolResult {
		if (moderator.totpConfirmed) return EnrolResult.AlreadyEnrolled
		val secret = Totp.generateSecret()
		moderators.setTotpSecret(moderator.id, secret, confirmed = false)
		return EnrolResult.Started(secret, Totp.provisioningUri(secret, moderator.username, issuer))
	}

	fun confirmEnrolment(moderator: Moderator, code: String): Boolean {
		if (moderator.totpConfirmed) return false
		val secret = moderators.totpSecretOf(moderator.id) ?: return false
		val step = Totp.verify(secret, code, clock.instant().epochSecond) ?: return false
		if (!moderators.burnTotpStep(moderator.id, step)) return false
		moderators.setTotpSecret(moderator.id, secret, confirmed = true)
		return true
	}

	fun invite(actor: Moderator, username: String, password: String, role: ModeratorRole): Moderator? {
		val trimmed = username.trim()
		if (trimmed.length !in ModerationRules.MIN_USERNAME_LENGTH..ModerationRules.MAX_USERNAME_LENGTH) {
			return null
		}
		if (password.length < Passwords.MIN_LENGTH) return null

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
		val losesAdmin = (role != null && target.role.isAdmin && !role.isAdmin) ||
			(disabled == true && target.role.isAdmin)
		if (losesAdmin && moderators.countAdmins(excluding = id) == 0) return false

		role?.let { moderators.setRole(id, it) }
		disabled?.let {
			moderators.setDisabled(id, it)
			// A disabled account must stop working now, not when its session happens to expire.
			if (it) moderators.closeAllSessions(id)
		}
		record(actor, ModActions.UPDATE_MODERATOR, ModActions.TARGET_MODERATOR, id, null) {
			put("username", target.username)
			role?.let { put("role", it.name) }
			disabled?.let { put("disabled", it) }
		}
		return true
	}

	/** For a moderator who lost their authenticator. Clears the secret so they enrol again. */
	fun resetTotp(actor: Moderator, id: String): Boolean {
		val target = moderators.find(id) ?: return false
		moderators.setTotpSecret(id, null, confirmed = false)
		moderators.closeAllSessions(id)
		record(actor, ModActions.RESET_TOTP, ModActions.TARGET_MODERATOR, id, null) {
			put("username", target.username)
		}
		return true
	}

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

		record(actor, ModActions.REMOVE_COMMENT, ModActions.TARGET_COMMENT, commentId.toString(), reason) {
			put("body", existing.body)
			put("author", existing.userId)
			put("work_id", existing.workId)
			put("lang", existing.lang)
			put("previous_state", existing.state.name)
		}
		comments.setState(commentId, CommentState.REMOVED)
		return true
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

		comments.restore(commentId, body, previous)
		record(actor, ModActions.RESTORE_COMMENT, ModActions.TARGET_COMMENT, commentId.toString(), reason) {
			put("restored_state", previous.name)
		}
		return true
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
		val user = identities.findById(userId) ?: return false
		if (!identities.setBanned(userId, banned = true, reason = reason)) return false
		val cleared = comments.tombstoneAllBy(userId)
		record(actor, ModActions.BAN_USER, ModActions.TARGET_USER, userId, reason) {
			put("nickname", user.nickname)
			put("comments_cleared", cleared)
		}
		return true
	}

	/** Comments are not restored: the ban deleted them, and a reversal does not un-delete words. */
	fun unbanUser(actor: Moderator, userId: String, reason: String?): Boolean {
		if (identities.findById(userId) == null) return false
		identities.setBanned(userId, banned = false, reason = null)
		record(actor, ModActions.UNBAN_USER, ModActions.TARGET_USER, userId, reason) {}
		return true
	}

	fun setShadowban(actor: Moderator, userId: String, shadowbanned: Boolean, reason: String?): Boolean {
		if (identities.findById(userId) == null) return false
		identities.setShadowbanned(userId, shadowbanned)
		val action = if (shadowbanned) ModActions.SHADOWBAN_USER else ModActions.UNSHADOWBAN_USER
		record(actor, action, ModActions.TARGET_USER, userId, reason) {}
		return true
	}

	fun resetNickname(actor: Moderator, userId: String, reason: String?): Boolean {
		val user = identities.findById(userId) ?: return false
		identities.clearNickname(userId)
		record(actor, ModActions.RESET_NICKNAME, ModActions.TARGET_USER, userId, reason) {
			put("previous", user.nickname)
		}
		return true
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
		identities.banDevice(ssaid, drm, actor.id, reason, now())
		record(actor, ModActions.BAN_DEVICE, ModActions.TARGET_DEVICE, fingerprint(ssaid), reason) {
			put("user_id", userId)
			put("has_drm_id", drm != null)
		}
		return true
	}

	/** Admin only. There is no in-app appeal surface by design, so reversal lives here and only here. */
	fun unbanDevice(actor: Moderator, fingerprint: String): Boolean {
		val match = identities.bannedDevices(MAX_DEVICE_BANS).firstOrNull { it.fingerprint == fingerprint }
			?: return false
		if (!identities.unbanDevice(match.ssaidHash)) return false
		record(actor, ModActions.UNBAN_DEVICE, ModActions.TARGET_DEVICE, fingerprint, null) {}
		return true
	}

	fun bannedDevices(): List<io.kotatsuredo.server.identity.BannedDevice> =
		identities.bannedDevices(MAX_DEVICE_BANS)

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
		if (!identities.reviewBanEvasionFlag(flagId, now())) return false
		record(actor, ModActions.REVIEW_FLAG, ModActions.TARGET_FLAG, "ban_evasion:$flagId", note) {}
		return true
	}

	fun reviewBrigade(actor: Moderator, flagId: Long, note: String?): Boolean {
		if (!queues.reviewBrigadeFlag(flagId)) return false
		record(actor, ModActions.REVIEW_FLAG, ModActions.TARGET_FLAG, "brigade:$flagId", note) {}
		return true
	}

	fun resolveDispute(actor: Moderator, disputeId: Long, note: String?): Boolean {
		if (!queues.resolveDispute(disputeId, actor.id)) return false
		record(actor, ModActions.RESOLVE_DISPUTE, ModActions.TARGET_DISPUTE, disputeId.toString(), note) {}
		return true
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
		const val FINGERPRINT_LENGTH = 16

		/** Roughly a day of used TOTP steps. Older ones cannot be replayed anyway. */
		const val TOTP_HISTORY_STEPS = 2880L
	}
}

private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null
