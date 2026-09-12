package io.kotatsuredo.server.identity

import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.comments.ContentFilter
import io.kotatsuredo.server.ratings.RatingService
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
	/**
	 * Optional so the identity domain still stands alone, but supplied in production: without it a
	 * deletion drops the rows instead of blanking them, and takes other people's replies along.
	 */
	private val comments: CommentRepository? = null,
	/** Same reason as [comments]: without it, a deleted rating goes on being counted. */
	private val ratings: RatingService? = null,
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
		if (repository.isDeviceBanned(ssaidHash)) {
			// No account is created. Nothing is written that a banned device could later use.
			return HelloOutcome.DeviceBanned
		}

		val secretHash = sha256(secret.toByteArray(Charsets.UTF_8))
		val existing = repository.findBySecretHash(secretHash)
		val now = now()

		if (existing != null) {
			repository.touch(existing.id, now)
			return HelloOutcome.Ok(existing, repository.trustTier(existing.id, now), created = false)
		}

		// The key is unknown. Before making a new account, ask whether this hardware already has one:
		// a phone whose app data was cleared has lost its key and nothing else, and without this it
		// collects a fresh account on every wipe.
		val restoredId = repository.lastUserForDevice(ssaidHash)
		if (restoredId != null) {
			repository.addSecret(restoredId, secretHash, SECRET_ORIGIN_RESTORE, now)
			repository.touch(restoredId, now)
			val identity = repository.findById(restoredId)
			if (identity != null) {
				log.info("Device restore: {} adopted a new key", restoredId)
				return HelloOutcome.Ok(
					identity,
					repository.trustTier(identity.id, now),
					created = false,
					restored = true,
				)
			}
		}

		val userId = userIdFrom(secretHash)
		val identity = repository.create(userId, secretHash, now)
		repository.touch(userId, now)

		val drmHash = device.drmId?.let(pepper::hash)
		repository.recordDevice(userId, ssaidHash, drmHash, now)

		// The factory-reset case: same hardware, new ANDROID_ID. Flagged, never blocked, because
		// DRM ids collide across devices and a collision would ban an innocent stranger.
		if (drmHash != null && repository.matchesBannedDrm(drmHash, ssaidHash)) {
			repository.flagBanEvasion(userId, drmHash, now)
			log.info("Ban-evasion flag raised for {} (DRM match on a new SSAID)", userId)
		}

		return HelloOutcome.Ok(identity, TrustTier.NEW, created = true)
	}

	/** Resolves an already-registered secret for ordinary requests. Does not create. */
	fun authenticate(secret: String): Identity? {
		val identity = repository.findBySecretHash(sha256(secret.toByteArray(Charsets.UTF_8)))
			?: return null
		repository.touch(identity.id, now())
		return identity
	}

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
		// Collected first: the rating rows go with the account, and the denormalised aggregate would
		// otherwise report the old count and average until somebody else happened to rate the work.
		val rated = ratings?.workIdsRatedBy(userId).orEmpty()
		val cleared = comments?.tombstoneAllBy(userId) ?: 0

		repository.delete(userId)

		rated.forEach { workId -> ratings?.recompute(workId) }
		log.info(
			"Erased identity {} at user request ({} comments tombstoned, {} ratings withdrawn)",
			userId, cleared, rated.size,
		)
	}

	fun get(userId: String): Identity? = repository.findById(userId)
}
