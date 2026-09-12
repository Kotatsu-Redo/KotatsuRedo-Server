package io.kotatsuredo.server.identity

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.ResultRow
// `where { }` scopes SqlExpressionBuilder as a receiver, but `deleteWhere { }` passes it as a
// parameter - so `eq` has to be imported explicitly for the delete below to resolve.
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import org.jetbrains.exposed.sql.Database as ExposedDatabase

class IdentityRepository(private val db: ExposedDatabase) {

	fun findBySecretHash(secretHash: ByteArray): Identity? = transaction(db) {
		// Resolved against user_secret, not app_user.secret_sha256: an account can hold more than one
		// key once a device restore has adopted a second.
		(UserSecrets innerJoin AppUsers)
			.selectAll()
			.where { UserSecrets.secretHash eq secretHash }
			.limit(1)
			.firstOrNull()
			?.toIdentity()
	}

	/**
	 * The account this hardware most recently used, for a phone that lost its key.
	 *
	 * Most recent, deliberately: one phone can have created several accounts - clearing app data
	 * makes a new one every time - and of those, the one the person was last using is the only
	 * defensible guess. Banned accounts are excluded so a restore cannot be a way back in.
	 */
	fun lastUserForDevice(ssaidHash: ByteArray): String? = transaction(db) {
		(AppDevices innerJoin AppUsers)
			.select(AppUsers.id, AppUsers.lastSeenAt)
			.where { (AppDevices.ssaidHash eq ssaidHash) and (AppUsers.isBanned eq false) }
			.orderBy(AppUsers.lastSeenAt, SortOrder.DESC)
			.limit(1)
			.firstOrNull()
			?.get(AppUsers.id)
	}

	/** Lets another key speak for an account. The keys it already had keep working. */
	fun addSecret(userId: String, secretHash: ByteArray, origin: String, now: OffsetDateTime): Unit =
		transaction(db) {
			UserSecrets.insertIgnore {
				it[UserSecrets.secretHash] = secretHash
				it[UserSecrets.userId] = userId
				it[UserSecrets.origin] = origin
				it[createdAt] = now
			}
			Unit
		}

	fun findById(userId: String): Identity? = transaction(db) {
		AppUsers.selectAll()
			.where { AppUsers.id eq userId }
			.limit(1)
			.firstOrNull()
			?.toIdentity()
	}

	fun create(userId: String, secretHash: ByteArray, now: OffsetDateTime): Identity = transaction(db) {
		AppUsers.insert {
			it[id] = userId
			it[AppUsers.secretHash] = secretHash
			it[createdAt] = now
			it[lastSeenAt] = now
			it[isBanned] = false
			it[isShadowbanned] = false
		}
		UserSecrets.insert {
			it[UserSecrets.secretHash] = secretHash
			it[UserSecrets.userId] = userId
			it[origin] = SECRET_ORIGIN_SIGNUP
			it[createdAt] = now
		}
		Identity(
			id = userId,
			nickname = null,
			createdAt = now,
			isBanned = false,
			banReason = null,
			isShadowbanned = false,
		)
	}

	/**
	 * Records that the user was seen today. Active *days* rather than a request counter, so trust
	 * cannot be earned in a single busy afternoon.
	 */
	fun touch(userId: String, now: OffsetDateTime): Unit = transaction(db) {
		AppUsers.update({ AppUsers.id eq userId }) { it[lastSeenAt] = now }
		UserActiveDays.insertIgnore {
			it[UserActiveDays.userId] = userId
			it[day] = now.toLocalDate()
		}
	}

	fun setNickname(userId: String, nickname: String): Unit = transaction(db) {
		AppUsers.update({ AppUsers.id eq userId }) { it[AppUsers.nickname] = nickname }
	}

	/** Erases the user. Comments are handled separately once they exist (M4a). */
	fun delete(userId: String): Unit = transaction(db) {
		AppUsers.deleteWhere { AppUsers.id eq userId }
	}

	fun trustTier(userId: String, now: OffsetDateTime): TrustTier = transaction(db) {
		val user = AppUsers.selectAll().where { AppUsers.id eq userId }.limit(1).firstOrNull()
			?: return@transaction TrustTier.NEW
		val activeDays = UserActiveDays.selectAll()
			.where { UserActiveDays.userId eq userId }
			.count()
		val createdAt = user[AppUsers.createdAt]
		when {
			createdAt.isAfter(now.minusHours(24)) || activeDays < MIN_ACTIVE_DAYS -> TrustTier.NEW
			createdAt.isBefore(now.minusDays(ESTABLISHED_AFTER_DAYS)) &&
				!user[AppUsers.isBanned] && !user[AppUsers.isShadowbanned] -> TrustTier.ESTABLISHED

			else -> TrustTier.NORMAL
		}
	}

	// -- moderation (M4b) ------------------------------------------------------------------------

	/**
	 * A ban is terminal and takes read access with it: [requireCaller] rejects a banned user on every
	 * endpoint, which is the deliberate consequence of identity being all-or-nothing (PLAN.md §1).
	 */
	fun setBanned(userId: String, banned: Boolean, reason: String?): Boolean = transaction(db) {
		AppUsers.update({ AppUsers.id eq userId }) {
			it[isBanned] = banned
			it[banReason] = if (banned) reason else null
		} > 0
	}

	/**
	 * Nothing in any response may reveal this. It is read on the write path to decide a comment's
	 * state, and in the vote recount to discard the user's votes - and nowhere else.
	 */
	fun setShadowbanned(userId: String, shadowbanned: Boolean): Boolean = transaction(db) {
		AppUsers.update({ AppUsers.id eq userId }) { it[isShadowbanned] = shadowbanned } > 0
	}

	/** Back to `anon#1234`, for a nickname that is an impersonation or a slur. */
	fun clearNickname(userId: String): Boolean = transaction(db) {
		AppUsers.update({ AppUsers.id eq userId }) { it[nickname] = null } > 0
	}

	fun unbanDevice(ssaidHash: ByteArray): Boolean = transaction(db) {
		DeviceBans.deleteWhere { DeviceBans.ssaidHash eq ssaidHash } > 0
	}

	fun bannedDevices(limit: Int): List<BannedDevice> = transaction(db) {
		DeviceBans.selectAll()
			.orderBy(DeviceBans.bannedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
			.limit(limit)
			.map {
				BannedDevice(
					ssaidHash = it[DeviceBans.ssaidHash],
					bannedAt = it[DeviceBans.bannedAt],
					byModerator = it[DeviceBans.byModerator],
					reason = it[DeviceBans.reason],
				)
			}
	}

	fun reviewBanEvasionFlag(id: Long, now: OffsetDateTime): Boolean = transaction(db) {
		BanEvasionFlags.update({ (BanEvasionFlags.id eq id) and (BanEvasionFlags.reviewedAt eq null) }) {
			it[reviewedAt] = now
		} > 0
	}

	// -- devices -------------------------------------------------------------------------------

	fun isDeviceBanned(ssaidHash: ByteArray): Boolean = transaction(db) {
		DeviceBans.selectAll().where { DeviceBans.ssaidHash eq ssaidHash }.limit(1).any()
	}

	/**
	 * True when a *different* device is banned that shares this DRM id - the factory-reset case.
	 * Never a block on its own: DRM ids collide across devices from the same manufacturer, so this
	 * only raises a flag for a human to confirm (PLAN.md §1).
	 */
	fun matchesBannedDrm(drmHash: ByteArray, ssaidHash: ByteArray): Boolean = transaction(db) {
		DeviceBans.selectAll()
			.where { (DeviceBans.drmHash eq drmHash) and (DeviceBans.ssaidHash neq ssaidHash) }
			.limit(1)
			.any()
	}

	fun recordDevice(userId: String, ssaidHash: ByteArray, drmHash: ByteArray?, now: OffsetDateTime) =
		transaction(db) {
			AppDevices.insertIgnore {
				it[AppDevices.userId] = userId
				it[AppDevices.ssaidHash] = ssaidHash
				it[AppDevices.drmHash] = drmHash
				it[firstSeenAt] = now
			}
			Unit
		}

	fun flagBanEvasion(userId: String, drmHash: ByteArray, now: OffsetDateTime): Unit = transaction(db) {
		BanEvasionFlags.insert {
			it[BanEvasionFlags.userId] = userId
			it[matchedDrmHash] = drmHash
			it[createdAt] = now
		}
	}

	fun banDevice(ssaidHash: ByteArray, drmHash: ByteArray?, moderator: String, reason: String?, now: OffsetDateTime) =
		transaction(db) {
			DeviceBans.insertIgnore {
				it[DeviceBans.ssaidHash] = ssaidHash
				it[DeviceBans.drmHash] = drmHash
				it[bannedAt] = now
				it[byModerator] = moderator
				it[DeviceBans.reason] = reason
			}
			Unit
		}

	fun deviceOf(userId: String): Pair<ByteArray, ByteArray?>? = transaction(db) {
		AppDevices.selectAll()
			.where { AppDevices.userId eq userId }
			.limit(1)
			.firstOrNull()
			?.let { it[AppDevices.ssaidHash] to it[AppDevices.drmHash] }
	}

	private fun ResultRow.toIdentity() = Identity(
		id = this[AppUsers.id],
		nickname = this[AppUsers.nickname],
		createdAt = this[AppUsers.createdAt],
		isBanned = this[AppUsers.isBanned],
		banReason = this[AppUsers.banReason],
		isShadowbanned = this[AppUsers.isShadowbanned],
	)

	private companion object {
		const val MIN_ACTIVE_DAYS = 3
		const val ESTABLISHED_AFTER_DAYS = 90L
	}
}
