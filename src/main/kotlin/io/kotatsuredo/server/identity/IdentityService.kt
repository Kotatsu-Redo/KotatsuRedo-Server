package io.kotatsuredo.server.identity

import io.kotatsuredo.server.comments.ContentFilter
import io.kotatsuredo.server.comments.FilterSurface
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime

private val log = LoggerFactory.getLogger("IdentityService")

class IdentityService(
	private val repository: IdentityRepository,
	private val pepper: DevicePepper,
	private val clock: Clock = Clock.systemUTC(),
	private val filter: ContentFilter = ContentFilter.PermitAll,
) {

	private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

	/**
	 * Resolve the bearer secret to an identity, creating one on first sight.
	 *
	 * There is no login endpoint and no session: the secret *is* the credential, so an unknown hash
	 * simply becomes a new user (PLAN.md §1).
	 */
	fun hello(secret: String, device: DeviceIdentifiers): HelloOutcome {
		val ssaidHash = pepper.hash(device.ssaid)
		val secretHash = sha256(secret.toByteArray(Charsets.UTF_8))
		val now = now()
		val userId = userIdFrom(secretHash)
		val drmHash = device.drmId?.let(pepper::hash)
		val result = repository.hello(secretHash, userId, ssaidHash, drmHash, now)
			?: return HelloOutcome.DeviceBanned

		// The factory-reset case: same hardware, new ANDROID_ID. Flagged, never blocked, because
		// DRM ids collide across devices and a collision would ban an innocent stranger.
		if (result.banEvasionFlagged) {
			log.info("Ban-evasion flag raised for {} (DRM match on a new SSAID)", userId)
		}

		return HelloOutcome.Ok(result.identity, result.tier, result.created)
	}

	/** Resolves an already-registered secret for ordinary requests. Does not create. */
	fun authenticate(secret: String): Identity? {
		return authenticateCaller(secret)?.first
	}

	/** Identity and tier in one database transaction, including the throttled activity touch. */
	fun authenticateCaller(secret: String): Pair<Identity, TrustTier>? =
		repository.authenticate(sha256(secret.toByteArray(Charsets.UTF_8)), now())

	fun trustTier(userId: String): TrustTier = repository.trustTier(userId, now())

	fun setNickname(userId: String, raw: String): NicknameResult {
		val result = Nicknames.validate(raw)
		if (result !is NicknameResult.Valid) return result

		// Language is not detected for a nickname - one word is far below the length where detection
		// means anything - so this sees the global `severe` list plus English. That is the honest
		// limit of what a wordlist can do here, and the panel's reset-nickname action is the rest.
		val context = ContentFilter.Context(userId, FilterSurface.NICKNAME)
		when (val verdict = filter.check(result.nickname, null, context)) {
			is ContentFilter.Verdict.Blocked ->
				return NicknameResult.Blocked(verdict.term, verdict.tier, verdict.blockId)

			// A `watch` hit on a nickname is not worth a queue item of its own; the name is visible on
			// every comment its owner writes, so a moderator will see it in the ordinary queues.
			is ContentFilter.Verdict.Flagged, ContentFilter.Verdict.Allowed -> Unit
		}

		repository.setNickname(userId, result.nickname)
		return result
	}

	/**
	 * "Delete everything about me" (PLAN.md §6). The secret is the proof, so this needs no email
	 * loop and takes effect immediately.
	 *
	 * Ratings and votes are erased outright. Comments are blanked first and then orphaned by the
	 * foreign key, leaving a tombstone that holds its place in the thread and carries nothing - which
	 * is the only way to remove one person's words without taking everyone's replies with them.
	 */
	fun deleteEverything(userId: String) {
		val deletion = repository.deleteEverything(userId)
		log.info(
			"Erased identity {} at user request ({} comments tombstoned, {} ratings withdrawn)",
			userId, deletion.commentsCleared, deletion.ratingsWithdrawn,
		)
	}

	fun get(userId: String): Identity? = repository.findById(userId)
}
