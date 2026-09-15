package io.kotatsuredo.server.identity

import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.scoring.Scoring
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
import java.sql.Connection
import javax.sql.DataSource
import org.jetbrains.exposed.sql.Database as ExposedDatabase

data class IdentityDeletionRecord(val commentsCleared: Int, val ratingsWithdrawn: Int)

class IdentityRepository(
	private val db: ExposedDatabase,
	private val dataSource: DataSource,
) {
	private data class RatingStats(val workId: Long, val count: Int, val mean: Double, val histogram: List<Int>)

	data class HelloRecord(
		val identity: Identity,
		val tier: TrustTier,
		val created: Boolean,
		val banEvasionFlagged: Boolean,
	)

	/**
	 * Handles the complete hello exchange in one transaction.
	 *
	 * In particular, a newly-created identity cannot be committed without its credential, active
	 * day and device row. The device-ban decision and signup also share one database unit of work.
	 *
	 * A null result means the SSAID is banned.
	 */
	fun hello(
		secretHash: ByteArray,
		userId: String,
		ssaidHash: ByteArray,
		drmHash: ByteArray?,
		now: OffsetDateTime,
	): HelloRecord? = transaction(db) {
		if (DeviceBans.selectAll().where { DeviceBans.ssaidHash eq ssaidHash }.limit(1).any()) {
			return@transaction null
		}

		val existing = (UserSecrets innerJoin AppUsers)
			.selectAll()
			.where { UserSecrets.secretHash eq secretHash }
			.limit(1)
			.firstOrNull()
		if (existing != null) {
			return@transaction helloForExisting(existing, now)
		}

		val created = AppUsers.insertIgnore {
			it[id] = userId
			it[AppUsers.secretHash] = secretHash
			it[createdAt] = now
			it[lastSeenAt] = now
			it[isBanned] = false
			it[isShadowbanned] = false
		}.insertedCount > 0
		if (!created) {
			// A concurrent hello with the same secret may have committed while this INSERT waited on
			// its unique index. ON CONFLICT makes that race an ordinary existing-account response.
			val concurrent = (UserSecrets innerJoin AppUsers)
				.selectAll()
				.where { UserSecrets.secretHash eq secretHash }
				.limit(1)
				.firstOrNull()
			checkNotNull(concurrent) { "identity id collision for a different credential" }
			return@transaction helloForExisting(concurrent, now)
		}
		UserSecrets.insert {
			it[UserSecrets.secretHash] = secretHash
			it[UserSecrets.userId] = userId
			it[origin] = SECRET_ORIGIN_SIGNUP
			it[createdAt] = now
		}
		UserActiveDays.insert {
			it[UserActiveDays.userId] = userId
			it[day] = now.toLocalDate()
		}
		AppDevices.insert {
			it[AppDevices.userId] = userId
			it[AppDevices.ssaidHash] = ssaidHash
			it[AppDevices.drmHash] = drmHash
			it[firstSeenAt] = now
		}

		val drmMatch = drmHash != null && DeviceBans.selectAll()
			.where { (DeviceBans.drmHash eq drmHash) and (DeviceBans.ssaidHash neq ssaidHash) }
			.limit(1)
			.any()
		if (drmMatch) {
			BanEvasionFlags.insert {
				it[BanEvasionFlags.userId] = userId
				it[matchedDrmHash] = requireNotNull(drmHash)
				it[createdAt] = now
			}
		}

		HelloRecord(
			identity = Identity(
				id = userId,
				nickname = null,
				createdAt = now,
				isBanned = false,
				banReason = null,
				isShadowbanned = false,
			),
			tier = TrustTier.NEW,
			created = true,
			banEvasionFlagged = drmMatch,
		)
	}

	private fun helloForExisting(row: ResultRow, now: OffsetDateTime): HelloRecord {
		val existingId = row[AppUsers.id]
		val lastSeen = row[AppUsers.lastSeenAt]
		if (!lastSeen.toLocalDate().isEqual(now.toLocalDate())) {
			UserActiveDays.insertIgnore {
				it[UserActiveDays.userId] = existingId
				it[day] = now.toLocalDate()
			}
		}
		if (lastSeen.isBefore(now.minusMinutes(ACTIVITY_TOUCH_MINUTES))) {
			AppUsers.update({ AppUsers.id eq existingId }) { it[lastSeenAt] = now }
		}
		val activeDays = UserActiveDays.select(UserActiveDays.day)
			.where { UserActiveDays.userId eq existingId }
			.limit(ESTABLISHED_MIN_ACTIVE_DAYS)
			.toList()
			.size.toLong()
		return HelloRecord(
			row.toIdentity(),
			tier(row, activeDays, now),
			created = false,
			banEvasionFlagged = false,
		)
	}

	fun findBySecretHash(secretHash: ByteArray): Identity? = transaction(db) {
		// Resolved against user_secret so credentials created by an older deployment remain valid.
		(UserSecrets innerJoin AppUsers)
			.selectAll()
			.where { UserSecrets.secretHash eq secretHash }
			.limit(1)
			.firstOrNull()
			?.toIdentity()
	}

	/**
	 * Resolves a credential, derives its trust tier, and records activity in one transaction.
	 * The last-seen timestamp is written at most once per fifteen minutes and the active-day insert
	 * only when the date changes, avoiding several writes and pool checkouts on every request.
	 */
	fun authenticate(secretHash: ByteArray, now: OffsetDateTime): Pair<Identity, TrustTier>? = transaction(db) {
		val row = (UserSecrets innerJoin AppUsers)
			.selectAll()
			.where { UserSecrets.secretHash eq secretHash }
			.limit(1)
			.firstOrNull()
			?: return@transaction null
		val userId = row[AppUsers.id]
		val lastSeen = row[AppUsers.lastSeenAt]
		if (!lastSeen.toLocalDate().isEqual(now.toLocalDate())) {
			UserActiveDays.insertIgnore {
				it[UserActiveDays.userId] = userId
				it[day] = now.toLocalDate()
			}
		}
		if (lastSeen.isBefore(now.minusMinutes(ACTIVITY_TOUCH_MINUTES))) {
			AppUsers.update({ AppUsers.id eq userId }) { it[lastSeenAt] = now }
		}
		val activeDays = UserActiveDays.select(UserActiveDays.day)
			.where { UserActiveDays.userId eq userId }
			.limit(ESTABLISHED_MIN_ACTIVE_DAYS)
			.toList()
			.size.toLong()
		row.toIdentity() to tier(row, activeDays, now)
	}

	fun findById(userId: String): Identity? = transaction(db) {
		AppUsers.selectAll()
			.where { AppUsers.id eq userId }
			.limit(1)
			.firstOrNull()
			?.toIdentity()
	}

	/**
	 * Erases an account and repairs every denormalised value in the same transaction. Locking the user
	 * first also serialises deletion against writes whose foreign keys still reference that account.
	 */
	fun deleteEverything(userId: String): IdentityDeletionRecord = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			val exists = connection.prepareStatement("SELECT id FROM app_user WHERE id = ? FOR UPDATE").use {
				it.setString(1, userId)
				it.executeQuery().use { rows -> rows.next() }
			}
			if (!exists) {
				connection.commit()
				return@use IdentityDeletionRecord(0, 0)
			}

			val ratedWorks = selectLongs(connection, "SELECT work_id FROM rating WHERE user_id = ?", userId)
				.distinct()
				.sorted()
			// Rating writes take the user key-share lock and then this per-work advisory lock. We hold
			// the stronger user lock already, so no new write by this account can slip into the set.
			ratedWorks.forEach { workId ->
				connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
					it.setLong(1, workId)
					it.execute()
				}
			}
			val votedComments = selectLongs(connection, "SELECT comment_id FROM comment_vote WHERE user_id = ?", userId)
			val commentsCleared = connection.prepareStatement(
				"UPDATE comment SET state = 2, body = '', deleted_at = now() WHERE user_id = ? AND state <> 2",
			).use {
				it.setString(1, userId)
				it.executeUpdate()
			}

			connection.prepareStatement("DELETE FROM app_user WHERE id = ?").use {
				it.setString(1, userId)
				it.executeUpdate()
			}
			recountVotes(connection, votedComments)
			recomputeRatings(connection, ratedWorks)
			connection.commit()
			IdentityDeletionRecord(commentsCleared, ratedWorks.size)
		} catch (error: Exception) {
			connection.rollback()
			throw error
		} finally {
			connection.autoCommit = true
		}
	}

	private fun selectLongs(connection: Connection, sql: String, userId: String): List<Long> =
		connection.prepareStatement(sql).use {
			it.setString(1, userId)
			it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getLong(1)) } }
		}

	private fun recountVotes(connection: Connection, commentIds: Collection<Long>) {
		if (commentIds.isEmpty()) return
		val ids = connection.createArrayOf("bigint", commentIds.distinct().toTypedArray())
		val counts = connection.prepareStatement(
			"""
			SELECT c.id,
			       count(*) FILTER (WHERE v.value = 1 AND u.id IS NOT NULL),
			       count(*) FILTER (WHERE v.value = -1 AND u.id IS NOT NULL)
			FROM comment c
			LEFT JOIN comment_vote v ON v.comment_id = c.id
			LEFT JOIN app_user u ON u.id = v.user_id AND NOT u.is_shadowbanned
			WHERE c.id = ANY (?)
			GROUP BY c.id
			""".trimIndent(),
		).use {
			it.setArray(1, ids)
			it.executeQuery().use { rows ->
				buildList {
					while (rows.next()) add(Triple(rows.getLong(1), rows.getInt(2), rows.getInt(3)))
				}
			}
		}
		connection.prepareStatement("UPDATE comment SET up = ?, down = ?, score = ? WHERE id = ?").use { update ->
			counts.forEach { (id, up, down) ->
				update.setInt(1, up)
				update.setInt(2, down)
				update.setFloat(3, Scoring.wilsonLowerBound(up.toDouble(), (up + down).toDouble()).toFloat())
				update.setLong(4, id)
				update.addBatch()
			}
			update.executeBatch()
		}
	}

	private fun recomputeRatings(connection: Connection, workIds: Collection<Long>) {
		if (workIds.isEmpty()) return
		val ids = connection.createArrayOf("bigint", workIds.distinct().toTypedArray())
		val globalMean = connection.createStatement().use { statement ->
			statement.executeQuery(
				"SELECT CASE WHEN sum(count) = 0 THEN 0 ELSE sum(value_sum)::float8 / sum(count) END " +
					"FROM rating_global_shard",
			).use { rows -> rows.next(); rows.getDouble(1) }
		}
		val aggregates = connection.prepareStatement(
			"""
			SELECT input.work_id, count(r.value), COALESCE(avg(r.value), 0),
			       count(r.value) FILTER (WHERE r.value BETWEEN 1 AND 2),
			       count(r.value) FILTER (WHERE r.value BETWEEN 3 AND 4),
			       count(r.value) FILTER (WHERE r.value BETWEEN 5 AND 6),
			       count(r.value) FILTER (WHERE r.value BETWEEN 7 AND 8),
			       count(r.value) FILTER (WHERE r.value BETWEEN 9 AND 10)
			FROM unnest(?::bigint[]) AS input(work_id)
			LEFT JOIN rating r ON r.work_id = input.work_id
			GROUP BY input.work_id
			""".trimIndent(),
		).use {
			it.setArray(1, ids)
			it.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						val count = rows.getInt(2)
						val mean = rows.getDouble(3)
						add(RatingStats(rows.getLong(1), count, mean, (4..8).map(rows::getInt)))
					}
				}
			}
		}
		connection.prepareStatement(
			"""
			INSERT INTO work_rating_agg (work_id, count, mean, bayesian, histogram, updated_at)
			VALUES (?, ?, ?, ?, ?, now())
			ON CONFLICT (work_id) DO UPDATE SET count = EXCLUDED.count, mean = EXCLUDED.mean,
				bayesian = EXCLUDED.bayesian, histogram = EXCLUDED.histogram, updated_at = now()
			""".trimIndent(),
		).use { update ->
			aggregates.forEach { stats ->
				update.setLong(1, stats.workId)
				update.setInt(2, stats.count)
				update.setDouble(3, stats.mean)
				update.setDouble(4, RatingService.bayesianAverage(stats.count, stats.mean, globalMean))
				update.setArray(5, connection.createArrayOf("integer", stats.histogram.toTypedArray()))
				update.addBatch()
			}
			update.executeBatch()
		}
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
		val activeDays = UserActiveDays.select(UserActiveDays.day)
			.where { UserActiveDays.userId eq userId }
			.limit(ESTABLISHED_MIN_ACTIVE_DAYS)
			.toList()
			.size.toLong()
		tier(user, activeDays, now)
	}

	private fun tier(user: ResultRow, activeDays: Long, now: OffsetDateTime): TrustTier {
		val createdAt = user[AppUsers.createdAt]
		return when {
			createdAt.isAfter(now.minusHours(24)) || activeDays < MIN_ACTIVE_DAYS -> TrustTier.NEW
			createdAt.isBefore(now.minusDays(ESTABLISHED_AFTER_DAYS)) &&
				activeDays >= ESTABLISHED_MIN_ACTIVE_DAYS &&
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

	fun setShadowbanned(
		connection: Connection,
		userId: String,
		shadowbanned: Boolean,
	): Boolean = connection.prepareStatement(
		"UPDATE app_user SET is_shadowbanned = ? WHERE id = ?",
	).use {
		it.setBoolean(1, shadowbanned)
		it.setString(2, userId)
		it.executeUpdate() > 0
	}

	/** Back to `anon#1234`, for a nickname that is an impersonation or a slur. */
	fun clearNickname(userId: String): Boolean = transaction(db) {
		AppUsers.update({ AppUsers.id eq userId }) { it[nickname] = null } > 0
	}

	fun unbanDevice(ssaidHash: ByteArray): Boolean = dataSource.connection.use { connection ->
		unbanDevice(connection, ssaidHash)
	}

	fun unbanDevice(connection: Connection, ssaidHash: ByteArray): Boolean =
		connection.prepareStatement("DELETE FROM device_ban WHERE ssaid_hash = ?").use { statement ->
			statement.setBytes(1, ssaidHash)
			statement.executeUpdate() > 0
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

	fun reviewBanEvasionFlag(id: Long, now: OffsetDateTime): Boolean = dataSource.connection.use { connection ->
		reviewBanEvasionFlag(connection, id, now)
	}

	fun reviewBanEvasionFlag(connection: Connection, id: Long, now: OffsetDateTime): Boolean =
		connection.prepareStatement(
			"UPDATE ban_evasion_flag SET reviewed_at = ? WHERE id = ? AND reviewed_at IS NULL",
		).use { statement ->
			statement.setObject(1, now)
			statement.setLong(2, id)
			statement.executeUpdate() > 0
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

	fun banDevice(ssaidHash: ByteArray, drmHash: ByteArray?, moderator: String, reason: String?, now: OffsetDateTime): Boolean =
		dataSource.connection.use { connection ->
			banDevice(connection, ssaidHash, drmHash, moderator, reason, now)
		}

	fun banDevice(
		connection: Connection,
		ssaidHash: ByteArray,
		drmHash: ByteArray?,
		moderator: String,
		reason: String?,
		now: OffsetDateTime,
	): Boolean = connection.prepareStatement(
		"""
		INSERT INTO device_ban (ssaid_hash, drm_hash, banned_at, by_moderator, reason)
		VALUES (?, ?, ?, ?, ?)
		ON CONFLICT DO NOTHING
		""".trimIndent(),
	).use { statement ->
		statement.setBytes(1, ssaidHash)
		statement.setBytes(2, drmHash)
		statement.setObject(3, now)
		statement.setString(4, moderator)
		statement.setString(5, reason)
		statement.executeUpdate() > 0
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
		const val ESTABLISHED_MIN_ACTIVE_DAYS = 30
		const val ESTABLISHED_AFTER_DAYS = 90L
		const val ACTIVITY_TOUCH_MINUTES = 15L
	}
}
