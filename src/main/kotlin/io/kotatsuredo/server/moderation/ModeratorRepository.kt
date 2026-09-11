package io.kotatsuredo.server.moderation

import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource

/** Everything about moderator accounts, sessions and the audit log. */
class ModeratorRepository(private val dataSource: DataSource) {

	// -- accounts --------------------------------------------------------------------------------

	fun count(): Int = dataSource.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery("SELECT count(*) FROM moderator").use { it.next(); it.getInt(1) }
		}
	}

	fun create(
		id: String,
		username: String,
		passwordHash: String,
		role: ModeratorRole,
		invitedBy: String?,
	): Moderator? = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			INSERT INTO moderator (id, username, password_hash, role, invited_by)
			VALUES (?, ?, ?, ?, ?)
			ON CONFLICT (username) DO NOTHING
			""".trimIndent(),
		).use { statement ->
			statement.setString(1, id)
			statement.setString(2, username)
			statement.setString(3, passwordHash)
			statement.setShort(4, role.code)
			statement.setString(5, invitedBy)
			if (statement.executeUpdate() == 0) return@use null
		}
		find(id)
	}

	fun find(id: String): Moderator? = query("WHERE id = ?") { it.setString(1, id) }.firstOrNull()

	fun findByUsername(username: String): Moderator? =
		query("WHERE lower(username) = lower(?)") { it.setString(1, username) }.firstOrNull()

	fun all(): List<Moderator> = query("ORDER BY created_at")

	/** Read separately and never returned to a caller, so a hash cannot leak through a response DTO. */
	fun passwordHashOf(id: String): String? = single("SELECT password_hash FROM moderator WHERE id = ?", id)

	fun totpSecretOf(id: String): String? = single("SELECT totp_secret FROM moderator WHERE id = ?", id)

	fun setPassword(id: String, passwordHash: String) =
		update("UPDATE moderator SET password_hash = ? WHERE id = ?") {
			it.setString(1, passwordHash)
			it.setString(2, id)
		}

	fun setTotpSecret(id: String, secret: String?, confirmed: Boolean) =
		update("UPDATE moderator SET totp_secret = ?, totp_confirmed = ? WHERE id = ?") {
			it.setString(1, secret)
			it.setBoolean(2, confirmed)
			it.setString(3, id)
		}

	fun setRole(id: String, role: ModeratorRole) =
		update("UPDATE moderator SET role = ? WHERE id = ?") {
			it.setShort(1, role.code)
			it.setString(2, id)
		}

	fun setDisabled(id: String, disabled: Boolean) =
		update("UPDATE moderator SET is_disabled = ? WHERE id = ?") {
			it.setBoolean(1, disabled)
			it.setString(2, id)
		}

	fun touchLogin(id: String, now: OffsetDateTime) =
		update("UPDATE moderator SET last_login_at = ? WHERE id = ?") {
			it.setObject(1, now)
			it.setString(2, id)
		}

	fun countAdmins(excluding: String? = null): Int = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"SELECT count(*) FROM moderator WHERE role = ? AND NOT is_disabled AND (? IS NULL OR id <> ?)",
		).use { statement ->
			statement.setShort(1, ModeratorRole.ADMIN.code)
			statement.setString(2, excluding)
			statement.setString(3, excluding)
			statement.executeQuery().use { it.next(); it.getInt(1) }
		}
	}

	// -- sessions --------------------------------------------------------------------------------

	fun openSession(tokenHash: ByteArray, moderatorId: String, expiresAt: OffsetDateTime) =
		update("INSERT INTO mod_session (token_sha256, moderator_id, expires_at) VALUES (?, ?, ?)") {
			it.setBytes(1, tokenHash)
			it.setString(2, moderatorId)
			it.setObject(3, expiresAt)
		}

	fun findSession(tokenHash: ByteArray, now: OffsetDateTime): ModSession? =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT $COLUMNS, s.expires_at
				FROM mod_session s JOIN moderator m ON m.id = s.moderator_id
				WHERE s.token_sha256 = ? AND s.expires_at > ?
				""".trimIndent(),
			).use { statement ->
				statement.setBytes(1, tokenHash)
				statement.setObject(2, now)
				statement.executeQuery().use { rows ->
					if (!rows.next()) return@use null
					ModSession(
						moderator = rows.toModerator(),
						expiresAt = rows.getObject("expires_at", OffsetDateTime::class.java),
					)
				}
			}
		}

	fun closeSession(tokenHash: ByteArray) =
		update("DELETE FROM mod_session WHERE token_sha256 = ?") { it.setBytes(1, tokenHash) }

	/** Used when an account is disabled or its password changes: every open session goes with it. */
	fun closeAllSessions(moderatorId: String) =
		update("DELETE FROM mod_session WHERE moderator_id = ?") { it.setString(1, moderatorId) }

	fun purgeExpiredSessions(now: OffsetDateTime) =
		update("DELETE FROM mod_session WHERE expires_at <= ?") { it.setObject(1, now) }

	/** @return false when this step was already used, which is a replayed code. */
	fun burnTotpStep(moderatorId: String, timeStep: Long): Boolean = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"INSERT INTO mod_totp_use (moderator_id, time_step) VALUES (?, ?) ON CONFLICT DO NOTHING",
		).use { statement ->
			statement.setString(1, moderatorId)
			statement.setLong(2, timeStep)
			statement.executeUpdate() > 0
		}
	}

	fun purgeOldTotpSteps(before: Long) =
		update("DELETE FROM mod_totp_use WHERE time_step < ?") { it.setLong(1, before) }

	// -- audit log -------------------------------------------------------------------------------

	fun record(
		moderatorId: String,
		action: String,
		targetType: String,
		targetId: String,
		reason: String?,
		detail: String?,
	): Long = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			INSERT INTO mod_action (moderator_id, action, target_type, target_id, reason, detail)
			VALUES (?, ?, ?, ?, ?, ?::jsonb)
			RETURNING id
			""".trimIndent(),
		).use { statement ->
			statement.setString(1, moderatorId)
			statement.setString(2, action)
			statement.setString(3, targetType)
			statement.setString(4, targetId)
			statement.setString(5, reason)
			statement.setString(6, detail)
			statement.executeQuery().use { it.next(); it.getLong(1) }
		}
	}

	/**
	 * @param moderatorId when set, only this moderator's own actions - what a non-admin sees.
	 * @param before an id cursor, so paging is stable while new actions are being written.
	 */
	fun actions(moderatorId: String?, before: Long?, limit: Int): List<ModActionRecord> =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT a.id, a.moderator_id, m.username, a.action, a.target_type, a.target_id,
				       a.reason, a.detail::text, a.created_at
				FROM mod_action a JOIN moderator m ON m.id = a.moderator_id
				WHERE (? IS NULL OR a.moderator_id = ?)
				  AND (? = 0 OR a.id < ?)
				ORDER BY a.id DESC
				LIMIT ?
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, moderatorId)
				statement.setString(2, moderatorId)
				statement.setLong(3, before ?: 0L)
				statement.setLong(4, before ?: 0L)
				statement.setInt(5, limit)
				statement.executeQuery().use { rows ->
					buildList {
						while (rows.next()) {
							add(
								ModActionRecord(
									id = rows.getLong(1),
									moderatorId = rows.getString(2),
									moderatorName = rows.getString(3),
									action = rows.getString(4),
									targetType = rows.getString(5),
									targetId = rows.getString(6),
									reason = rows.getString(7),
									detail = rows.getString(8),
									createdAt = rows.getObject(9, OffsetDateTime::class.java),
								),
							)
						}
					}
				}
			}
		}

	/**
	 * The `detail` of the most recent removal of this comment.
	 *
	 * The audit log doubles as the undo buffer: removal blanks the body in place, so this snapshot is
	 * the only surviving copy of the text and the only way a restore can put it back.
	 */
	fun lastRemovalDetail(targetId: String): String? = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT detail::text FROM mod_action
			WHERE target_type = ? AND target_id = ? AND action = ?
			ORDER BY id DESC LIMIT 1
			""".trimIndent(),
		).use { statement ->
			statement.setString(1, ModActions.TARGET_COMMENT)
			statement.setString(2, targetId)
			statement.setString(3, ModActions.REMOVE_COMMENT)
			statement.executeQuery().use { if (it.next()) it.getString(1) else null }
		}
	}

	// -- plumbing --------------------------------------------------------------------------------

	private fun query(clause: String, bind: (java.sql.PreparedStatement) -> Unit = {}): List<Moderator> =
		dataSource.connection.use { connection ->
			connection.prepareStatement("SELECT $COLUMNS FROM moderator m $clause").use { statement ->
				bind(statement)
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.toModerator()) }
				}
			}
		}

	private fun single(sql: String, id: String): String? = dataSource.connection.use { connection ->
		connection.prepareStatement(sql).use { statement ->
			statement.setString(1, id)
			statement.executeQuery().use { if (it.next()) it.getString(1) else null }
		}
	}

	private fun update(sql: String, bind: (java.sql.PreparedStatement) -> Unit) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(sql).use { statement ->
				bind(statement)
				statement.executeUpdate()
			}
		}
	}

	private fun ResultSet.toModerator() = Moderator(
		id = getString("id"),
		username = getString("username"),
		role = ModeratorRole.of(getShort("role")),
		isDisabled = getBoolean("is_disabled"),
		totpConfirmed = getBoolean("totp_confirmed"),
		createdAt = getObject("created_at", OffsetDateTime::class.java),
		lastLoginAt = getObject("last_login_at", OffsetDateTime::class.java),
		invitedBy = getString("invited_by"),
	)

	private companion object {
		/** Never includes `password_hash` or `totp_secret`; those have their own accessors. */
		const val COLUMNS =
			"m.id, m.username, m.role, m.is_disabled, m.totp_confirmed, m.created_at, " +
				"m.last_login_at, m.invited_by"
	}
}
