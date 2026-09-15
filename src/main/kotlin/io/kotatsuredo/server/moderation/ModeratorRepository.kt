package io.kotatsuredo.server.moderation

import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Everything about moderator accounts, sessions and the audit log. */
class ModeratorRepository(
	private val dataSource: DataSource,
	private val totpCipher: TotpSecretCipher,
) {

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

	fun totpSecretOf(id: String): String? =
		single("SELECT totp_secret FROM moderator WHERE id = ?", id)?.let(totpCipher::decrypt)

	fun setPassword(id: String, passwordHash: String) =
		update("UPDATE moderator SET password_hash = ? WHERE id = ?") {
			it.setString(1, passwordHash)
			it.setString(2, id)
		}

	/** Changes the credential and revokes every session in the same transaction. */
	fun changePasswordAndCloseSessions(id: String, passwordHash: String): Boolean =
		dataSource.connection.use { connection ->
			connection.autoCommit = false
			try {
				val changed = connection.prepareStatement(
					"UPDATE moderator SET password_hash = ? WHERE id = ?",
				).use { statement ->
					statement.setString(1, passwordHash)
					statement.setString(2, id)
					statement.executeUpdate() > 0
				}
				if (!changed) {
					connection.rollback()
					return@use false
				}
				connection.prepareStatement("DELETE FROM mod_session WHERE moderator_id = ?").use { statement ->
					statement.setString(1, id)
					statement.executeUpdate()
				}
				connection.commit()
				true
			} catch (error: Exception) {
				connection.rollback()
				throw error
			} finally {
				connection.autoCommit = true
			}
		}

	fun setTotpSecret(id: String, secret: String?, confirmed: Boolean) =
		update("UPDATE moderator SET totp_secret = ?, totp_confirmed = ? WHERE id = ?") {
			it.setString(1, secret?.let(totpCipher::encrypt))
			it.setBoolean(2, confirmed)
			it.setString(3, id)
		}

	/**
	 * Starts or resumes enrolment for one session. A sibling password-only session can see that
	 * enrolment is in progress, but can never retrieve or replace the owning session's seed.
	 */
	fun startTotpEnrolment(id: String, tokenHash: ByteArray, candidateSecret: String): String? =
		dataSource.connection.use { connection ->
			connection.autoCommit = false
			try {
				val account = connection.prepareStatement(
					"SELECT totp_secret, totp_confirmed FROM moderator WHERE id = ? FOR UPDATE",
				).use { statement ->
					statement.setString(1, id)
					statement.executeQuery().use { rows ->
						if (!rows.next()) null else rows.getString(1) to rows.getBoolean(2)
					}
				}
				if (account == null || account.second) {
					connection.rollback()
					return@use null
				}
				val sessionIsLive = connection.prepareStatement(
					"""
					SELECT 1 FROM mod_session
					WHERE token_sha256 = ? AND moderator_id = ? AND expires_at > now()
					""".trimIndent(),
				).use { statement ->
					statement.setBytes(1, tokenHash)
					statement.setString(2, id)
					statement.executeQuery().use { it.next() }
				}
				if (!sessionIsLive) {
					connection.rollback()
					return@use null
				}
				val owner = connection.prepareStatement(
					"""
					SELECT token_sha256 FROM mod_session
					WHERE moderator_id = ? AND totp_enrolment_owner AND expires_at > now()
					LIMIT 1
					""".trimIndent(),
				).use { statement ->
					statement.setString(1, id)
					statement.executeQuery().use { rows -> if (rows.next()) rows.getBytes(1) else null }
				}
				if (account.first != null && owner != null) {
					connection.rollback()
					return@use if (owner.contentEquals(tokenHash)) {
						totpCipher.decrypt(account.first)
					} else {
						null
					}
				}

				// No live owner remains. Clear an expired owner's marker and start a fresh attempt.
				connection.prepareStatement(
					"UPDATE mod_session SET totp_enrolment_owner = FALSE WHERE moderator_id = ?",
				).use { statement ->
					statement.setString(1, id)
					statement.executeUpdate()
				}
				val claimed = connection.prepareStatement(
					"""
					UPDATE mod_session SET totp_enrolment_owner = TRUE
					WHERE token_sha256 = ? AND moderator_id = ? AND expires_at > now()
					""".trimIndent(),
				).use { statement ->
					statement.setBytes(1, tokenHash)
					statement.setString(2, id)
					statement.executeUpdate() == 1
				}
				if (!claimed) {
					connection.rollback()
					return@use null
				}
				connection.prepareStatement(
					"UPDATE moderator SET totp_secret = ?, totp_confirmed = FALSE WHERE id = ?",
				).use { statement ->
					statement.setString(1, totpCipher.encrypt(candidateSecret))
					statement.setString(2, id)
					statement.executeUpdate()
				}
				connection.commit()
				candidateSecret
			} catch (error: Exception) {
				connection.rollback()
				throw error
			} finally {
				connection.autoCommit = true
			}
		}

	/** Encrypts plaintext seeds left by versions that predate application-level encryption. */
	fun encryptLegacyTotpSecrets(): Int = dataSource.connection.use { connection ->
		val legacy = connection.createStatement().use { statement ->
			statement.executeQuery("SELECT id, totp_secret FROM moderator WHERE totp_secret IS NOT NULL").use { rows ->
				buildList {
					while (rows.next()) {
						val stored = rows.getString(2)
						if (totpCipher.isEncrypted(stored)) {
							// Fail startup immediately if the configured key cannot read existing credentials.
							totpCipher.decrypt(stored)
						} else {
							add(rows.getString(1) to stored)
						}
					}
				}
			}
		}
		connection.prepareStatement("UPDATE moderator SET totp_secret = ? WHERE id = ?").use { update ->
			legacy.forEach { (id, secret) ->
				update.setString(1, totpCipher.encrypt(secret))
				update.setString(2, id)
				update.addBatch()
			}
			update.executeBatch().sum()
		}
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

	/**
	 * Applies an account edit while holding a table lock, so two admins cannot concurrently demote
	 * each other after both observed that another enabled admin existed.
	 */
	fun updateAccountSafely(id: String, role: ModeratorRole?, disabled: Boolean?): Boolean =
		dataSource.connection.use { connection ->
			connection.autoCommit = false
			try {
				connection.createStatement().use {
					it.execute("LOCK TABLE moderator IN SHARE ROW EXCLUSIVE MODE")
				}
				val current = connection.prepareStatement(
					"SELECT role, is_disabled FROM moderator WHERE id = ?",
				).use { statement ->
					statement.setString(1, id)
					statement.executeQuery().use { rows ->
						if (!rows.next()) null else ModeratorRole.of(rows.getShort(1)) to rows.getBoolean(2)
					}
				} ?: run {
					connection.rollback()
					return@use false
				}

				val resultingRole = role ?: current.first
				val resultingDisabled = disabled ?: current.second
				val losesEnabledAdmin = current.first.isAdmin && !current.second &&
					(!resultingRole.isAdmin || resultingDisabled)
				if (losesEnabledAdmin) {
					val enabledAdmins = connection.prepareStatement(
						"SELECT count(*) FROM moderator WHERE role = ? AND NOT is_disabled",
					).use { statement ->
						statement.setShort(1, ModeratorRole.ADMIN.code)
						statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
					}
					if (enabledAdmins <= 1) {
						connection.rollback()
						return@use false
					}
				}

				connection.prepareStatement(
					"UPDATE moderator SET role = ?, is_disabled = ? WHERE id = ?",
				).use { statement ->
					statement.setShort(1, resultingRole.code)
					statement.setBoolean(2, resultingDisabled)
					statement.setString(3, id)
					statement.executeUpdate()
				}
				if (resultingDisabled) {
					connection.prepareStatement("DELETE FROM mod_session WHERE moderator_id = ?").use { statement ->
						statement.setString(1, id)
						statement.executeUpdate()
					}
				}
				connection.commit()
				true
			} catch (error: Exception) {
				connection.rollback()
				throw error
			} finally {
				connection.autoCommit = true
			}
		}

	// -- sessions --------------------------------------------------------------------------------

	fun openSession(
		tokenHash: ByteArray,
		moderatorId: String,
		expiresAt: OffsetDateTime,
		mfaVerified: Boolean = false,
	) =
		update(
			"INSERT INTO mod_session (token_sha256, moderator_id, expires_at, mfa_verified) VALUES (?, ?, ?, ?)",
		) {
			it.setBytes(1, tokenHash)
			it.setString(2, moderatorId)
			it.setObject(3, expiresAt)
			it.setBoolean(4, mfaVerified)
		}

	fun findSession(tokenHash: ByteArray, now: OffsetDateTime): ModSession? =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT $COLUMNS, s.expires_at, s.mfa_verified
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
						mfaVerified = rows.getBoolean("mfa_verified"),
					)
				}
			}
		}

	fun closeSession(tokenHash: ByteArray) =
		update("DELETE FROM mod_session WHERE token_sha256 = ?") { it.setBytes(1, tokenHash) }

	/** Used when an account is disabled or its password changes: every open session goes with it. */
	fun closeAllSessions(moderatorId: String) =
		update("DELETE FROM mod_session WHERE moderator_id = ?") { it.setString(1, moderatorId) }

	/**
	 * Confirms enrolment and upgrades only the session that supplied the valid code. Every other
	 * password-only session is revoked in the same transaction.
	 */
	fun confirmTotpAndPromoteSession(
		moderatorId: String,
		tokenHash: ByteArray,
		expectedSecret: String,
		timeStep: Long,
	): Boolean = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			val storedSecret = connection.prepareStatement(
				"SELECT totp_secret, totp_confirmed FROM moderator WHERE id = ? FOR UPDATE",
			).use { statement ->
				statement.setString(1, moderatorId)
				statement.executeQuery().use { rows ->
					if (!rows.next() || rows.getBoolean(2)) null else rows.getString(1)?.let(totpCipher::decrypt)
				}
			}
			if (storedSecret != expectedSecret) {
				connection.rollback()
				return@use false
			}
			val promoted = connection.prepareStatement(
				"""
				UPDATE mod_session SET mfa_verified = TRUE, totp_enrolment_owner = FALSE
				WHERE token_sha256 = ? AND moderator_id = ? AND totp_enrolment_owner
				  AND expires_at > now()
				""".trimIndent(),
			).use { statement ->
				statement.setBytes(1, tokenHash)
				statement.setString(2, moderatorId)
				statement.executeUpdate() == 1
			}
			if (!promoted) {
				connection.rollback()
				return@use false
			}
			val freshStep = connection.prepareStatement(
				"INSERT INTO mod_totp_use (moderator_id, time_step) VALUES (?, ?) ON CONFLICT DO NOTHING",
			).use { statement ->
				statement.setString(1, moderatorId)
				statement.setLong(2, timeStep)
				statement.executeUpdate() == 1
			}
			if (!freshStep) {
				connection.rollback()
				return@use false
			}
			connection.prepareStatement(
				"UPDATE moderator SET totp_confirmed = TRUE WHERE id = ?",
			).use { statement ->
				statement.setString(1, moderatorId)
				statement.executeUpdate()
			}
			connection.prepareStatement(
				"DELETE FROM mod_session WHERE moderator_id = ? AND token_sha256 <> ?",
			).use { statement ->
				statement.setString(1, moderatorId)
				statement.setBytes(2, tokenHash)
				statement.executeUpdate()
			}
			connection.commit()
			true
		} catch (error: Exception) {
			connection.rollback()
			throw error
		} finally {
			connection.autoCommit = true
		}
	}

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
		insertAudit(connection, moderatorId, action, targetType, targetId, reason, detail)
	}

	fun <T : Any> mutateWithAudit(
		moderatorId: String,
		action: String,
		targetType: String,
		targetId: String,
		reason: String?,
		detail: (T) -> String,
		mutation: (java.sql.Connection) -> T?,
	): T? = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			val result = mutation(connection)
			if (result == null) {
				connection.rollback()
				return@use null
			}
			insertAudit(connection, moderatorId, action, targetType, targetId, reason, detail(result))
			connection.commit()
			result
		} catch (error: Exception) {
			connection.rollback()
			throw error
		} finally {
			connection.autoCommit = true
		}
	}

	fun resetTotpWithAudit(moderatorId: String, targetId: String): Boolean =
		mutateWithAudit(
			moderatorId = moderatorId,
			action = ModActions.RESET_TOTP,
			targetType = ModActions.TARGET_MODERATOR,
			targetId = targetId,
			reason = null,
			detail = { username -> buildJsonObject { put("username", username) }.toString() },
		) { connection ->
			val username = connection.prepareStatement(
				"SELECT username FROM moderator WHERE id = ? FOR UPDATE",
			).use { statement ->
				statement.setString(1, targetId)
				statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
			} ?: return@mutateWithAudit null

			connection.prepareStatement(
				"UPDATE moderator SET totp_secret = NULL, totp_confirmed = FALSE WHERE id = ?",
			).use { statement ->
				statement.setString(1, targetId)
				statement.executeUpdate()
			}
			connection.prepareStatement("DELETE FROM mod_session WHERE moderator_id = ?").use { statement ->
				statement.setString(1, targetId)
				statement.executeUpdate()
			}
			username
		} != null

	/** A user ban, comment tombstone, and audit row are one indivisible moderation action. */
	fun banUserWithAudit(moderatorId: String, userId: String, reason: String): Boolean =
		dataSource.connection.use connectionUse@ { connection ->
			connection.autoCommit = false
			try {
				val nickname = connection.prepareStatement(
					"SELECT nickname FROM app_user WHERE id = ? FOR UPDATE",
				).use { statement ->
					statement.setString(1, userId)
					statement.executeQuery().use { rows ->
						if (!rows.next()) {
							connection.rollback()
							return@connectionUse false
						}
						rows.getString(1)
					}
				}
				connection.prepareStatement(
					"UPDATE app_user SET is_banned = TRUE, ban_reason = ? WHERE id = ?",
				).use { statement ->
					statement.setString(1, reason)
					statement.setString(2, userId)
					statement.executeUpdate()
				}
				val cleared = connection.prepareStatement(
					"UPDATE comment SET state = 2, body = '', deleted_at = now() WHERE user_id = ? AND state <> 2",
				).use { statement ->
					statement.setString(1, userId)
					statement.executeUpdate()
				}
				val detail = buildJsonObject {
					nickname?.let { put("nickname", it) }
					put("comments_cleared", cleared)
				}.toString()
				insertAudit(
					connection, moderatorId, ModActions.BAN_USER, ModActions.TARGET_USER,
					userId, reason, detail,
				)
				connection.commit()
				true
			} catch (error: Exception) {
				connection.rollback()
				throw error
			} finally {
				connection.autoCommit = true
			}
		}

	fun unbanUserWithAudit(moderatorId: String, userId: String, reason: String?): Boolean =
		userUpdateWithAudit(
			moderatorId, userId, ModActions.UNBAN_USER, reason,
			"UPDATE app_user SET is_banned = FALSE, ban_reason = NULL WHERE id = ?",
			detail = "{}",
		)

	fun resetNicknameWithAudit(
		moderatorId: String,
		userId: String,
		reason: String?,
		previousNickname: String?,
	): Boolean = userUpdateWithAudit(
		moderatorId, userId, ModActions.RESET_NICKNAME, reason,
		"UPDATE app_user SET nickname = NULL WHERE id = ?",
		buildJsonObject { previousNickname?.let { put("previous", it) } }.toString(),
	)

	private fun userUpdateWithAudit(
		moderatorId: String,
		userId: String,
		action: String,
		reason: String?,
		sql: String,
		detail: String,
	): Boolean = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			val changed = connection.prepareStatement(sql).use { statement ->
				statement.setString(1, userId)
				statement.executeUpdate() > 0
			}
			if (!changed) {
				connection.rollback()
				return@use false
			}
			insertAudit(connection, moderatorId, action, ModActions.TARGET_USER, userId, reason, detail)
			connection.commit()
			true
		} catch (error: Exception) {
			connection.rollback()
			throw error
		} finally {
			connection.autoCommit = true
		}
	}

	fun removeCommentWithAudit(
		moderatorId: String,
		commentId: Long,
		reason: String,
		detail: String,
	): Boolean = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			val changed = connection.prepareStatement(
				"UPDATE comment SET state = 2, body = '', deleted_at = now() WHERE id = ? AND state <> 2",
			).use { statement ->
				statement.setLong(1, commentId)
				statement.executeUpdate() > 0
			}
			if (!changed) {
				connection.rollback()
				return@use false
			}
			insertAudit(
				connection, moderatorId, ModActions.REMOVE_COMMENT, ModActions.TARGET_COMMENT,
				commentId.toString(), reason, detail,
			)
			connection.commit()
			true
		} catch (e: Exception) {
			connection.rollback()
			throw e
		}
	}

	fun restoreCommentWithAudit(
		moderatorId: String,
		commentId: Long,
		body: String,
		state: Short,
		reason: String?,
		detail: String,
	): Boolean = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			val changed = connection.prepareStatement(
				"UPDATE comment SET state = ?, body = ?, deleted_at = NULL WHERE id = ? AND state = 2",
			).use { statement ->
				statement.setShort(1, state)
				statement.setString(2, body)
				statement.setLong(3, commentId)
				statement.executeUpdate() > 0
			}
			if (!changed) {
				connection.rollback()
				return@use false
			}
			insertAudit(
				connection, moderatorId, ModActions.RESTORE_COMMENT, ModActions.TARGET_COMMENT,
				commentId.toString(), reason, detail,
			)
			connection.commit()
			true
		} catch (e: Exception) {
			connection.rollback()
			throw e
		}
	}

	private fun insertAudit(
		connection: java.sql.Connection,
		moderatorId: String,
		action: String,
		targetType: String,
		targetId: String,
		reason: String?,
		detail: String?,
	): Long =
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
