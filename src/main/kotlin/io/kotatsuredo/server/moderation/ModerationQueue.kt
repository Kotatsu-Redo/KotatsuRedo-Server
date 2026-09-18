package io.kotatsuredo.server.moderation

import io.kotatsuredo.server.identity.Nicknames
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * A comment as a moderator needs to see it: the text, who wrote it, what it is attached to, and
 * enough about the author to judge whether this is a bad day or a pattern.
 *
 * Deliberately shows shadowed and removed comments, unlike every user-facing read path. The panel is
 * the one place where the full picture is the point.
 */
data class QueuedComment(
	val id: Long,
	val workId: Long,
	val workTitle: String,
	val chapterId: Long?,
	val body: String,
	val lang: String?,
	val state: Short,
	val up: Int,
	val down: Int,
	val score: Double,
	val createdAt: OffsetDateTime,
	val authorId: String,
	val authorName: String,
	val authorIsBanned: Boolean,
	val authorIsShadowbanned: Boolean,
	/** How many of this author's comments a moderator has already removed. The pattern signal. */
	val authorRemovedCount: Int,
	/** The `watch` term that flagged it, if any. */
	val flaggedRule: String? = null,
)

data class QueuedBanEvasion(
	val id: Long,
	val userId: String,
	val userName: String,
	val createdAt: OffsetDateTime,
	val userCommentCount: Int,
)

data class QueuedBrigade(
	val id: Long,
	val workId: Long,
	val workTitle: String,
	val detectedAt: OffsetDateTime,
	val recentCount: Int,
	val baseline: Double,
	val extremeShare: Double,
	val newUserShare: Double,
)

data class QueuedDispute(
	val id: Long,
	val workId: Long,
	val workTitle: String,
	val otherWorkId: Long?,
	val otherWorkTitle: String?,
	val kind: String,
	val reportedBy: String?,
	val note: String?,
	val createdAt: OffsetDateTime,
)

/**
 * The read side of the panel.
 *
 * Its own class rather than methods on the domain repositories, because every one of these queries
 * joins across three or four tables and exists only to fill a screen - putting them next to the
 * single-purpose reads the app uses would blur what each repository is for.
 */
class ModerationQueueRepository(private val dataSource: DataSource) {

	/**
	 * The primary triage view: what the community has voted down hardest, recently.
	 *
	 * With no report button, this *is* the report queue - dislikes are the signal (PLAN.md §6). Sorted
	 * by raw dislikes rather than by the Wilson score, because ranking deliberately buries a bad
	 * comment and burying it is exactly what must not hide it from a moderator.
	 */
	fun mostDisliked(hours: Int, minDislikes: Int, limit: Int): List<QueuedComment> =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				$COMMENT_SELECT
				WHERE c.created_at > now() - make_interval(hours => ?)
				  AND c.down >= ?
				  AND c.state <> 2
				  AND c.dismissed_at IS NULL
				ORDER BY c.down DESC, c.score ASC, c.created_at DESC
				LIMIT ?
				""".trimIndent(),
			).use { statement ->
				statement.setInt(1, hours)
				statement.setInt(2, minDislikes)
				statement.setInt(3, limit)
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.toQueuedComment()) }
				}
			}
		}

	/**
	 * Everything, newest first.
	 *
	 * The actual safety net: at this volume a person can skim it, and it is the only queue that
	 * catches something nobody happened to downvote.
	 */
	fun firehose(before: Long?, limit: Int): List<QueuedComment> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			$COMMENT_SELECT
			WHERE (? = 0 OR c.id < ?)
			ORDER BY c.id DESC
			LIMIT ?
			""".trimIndent(),
		).use { statement ->
			statement.setLong(1, before ?: 0L)
			statement.setLong(2, before ?: 0L)
			statement.setInt(3, limit)
			statement.executeQuery().use { rows ->
				buildList { while (rows.next()) add(rows.toQueuedComment()) }
			}
		}
	}

	/**
	 * Comments a `watch` term flagged.
	 *
	 * The whole point of the tier: these published normally and their authors were told nothing, so
	 * this list is the only place they surface.
	 */
	fun flagged(limit: Int): List<QueuedComment> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"$COMMENT_SELECT WHERE c.flagged_rule IS NOT NULL AND c.state <> 2 AND c.dismissed_at IS NULL " +
				"ORDER BY c.created_at DESC LIMIT ?",
		).use { statement ->
			statement.setInt(1, limit)
			statement.executeQuery().use { rows ->
				buildList { while (rows.next()) add(rows.toQueuedComment()) }
			}
		}
	}

	fun comment(id: Long): QueuedComment? = dataSource.connection.use { connection ->
		connection.prepareStatement("$COMMENT_SELECT WHERE c.id = ?").use { statement ->
			statement.setLong(1, id)
			statement.executeQuery().use { if (it.next()) it.toQueuedComment() else null }
		}
	}

	/** Every comment by one author, which is how "is this a pattern" gets answered. */
	fun byAuthor(userId: String, limit: Int): List<QueuedComment> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"$COMMENT_SELECT WHERE c.user_id = ? ORDER BY c.created_at DESC LIMIT ?",
		).use { statement ->
			statement.setString(1, userId)
			statement.setInt(2, limit)
			statement.executeQuery().use { rows ->
				buildList { while (rows.next()) add(rows.toQueuedComment()) }
			}
		}
	}

	fun banEvasionFlags(limit: Int): List<QueuedBanEvasion> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT f.id, f.user_id, u.nickname, f.created_at,
			       (SELECT count(*) FROM comment c WHERE c.user_id = f.user_id) AS comments
			FROM ban_evasion_flag f JOIN app_user u ON u.id = f.user_id
			WHERE f.reviewed_at IS NULL
			ORDER BY f.created_at DESC
			LIMIT ?
			""".trimIndent(),
		).use { statement ->
			statement.setInt(1, limit)
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(
							QueuedBanEvasion(
								id = rows.getLong(1),
								userId = rows.getString(2),
								userName = displayName(rows.getString(3), rows.getString(2)),
								createdAt = rows.getObject(4, OffsetDateTime::class.java),
								userCommentCount = rows.getInt(5),
							),
						)
					}
				}
			}
		}
	}

	fun brigadeFlags(limit: Int): List<QueuedBrigade> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT f.id, f.work_id, w.canonical_title, f.detected_at, f.recent_count, f.baseline,
			       f.extreme_share, f.new_user_share
			FROM rating_brigade_flag f JOIN work w ON w.id = f.work_id
			WHERE f.reviewed_at IS NULL
			ORDER BY f.detected_at DESC
			LIMIT ?
			""".trimIndent(),
		).use { statement ->
			statement.setInt(1, limit)
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(
							QueuedBrigade(
								id = rows.getLong(1),
								workId = rows.getLong(2),
								workTitle = rows.getString(3),
								detectedAt = rows.getObject(4, OffsetDateTime::class.java),
								recentCount = rows.getInt(5),
								baseline = rows.getDouble(6),
								extremeShare = rows.getDouble(7),
								newUserShare = rows.getDouble(8),
							),
						)
					}
				}
			}
		}
	}

	fun openDisputes(limit: Int): List<QueuedDispute> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT d.id, d.work_id, w.canonical_title, d.other_work, o.canonical_title, d.kind,
			       d.reported_by, d.note, d.created_at
			FROM work_link_dispute d
			JOIN work w ON w.id = d.work_id
			LEFT JOIN work o ON o.id = d.other_work
			WHERE d.resolved_at IS NULL
			ORDER BY d.created_at
			LIMIT ?
			""".trimIndent(),
		).use { statement ->
			statement.setInt(1, limit)
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(
							QueuedDispute(
								id = rows.getLong(1),
								workId = rows.getLong(2),
								workTitle = rows.getString(3),
								otherWorkId = rows.getLong(4).takeUnless { rows.wasNull() },
								otherWorkTitle = rows.getString(5),
								kind = rows.getString(6),
								reportedBy = rows.getString(7),
								note = rows.getString(8),
								createdAt = rows.getObject(9, OffsetDateTime::class.java),
							),
						)
					}
				}
			}
		}
	}

	/**
	 * Files a dispute, collapsing duplicates: a hundred users reporting the same bad merge is one
	 * queue item, not a hundred.
	 */
	fun fileDispute(
		workId: Long,
		otherWorkId: Long?,
		kind: String,
		reportedBy: String?,
		note: String?,
	): Boolean = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			INSERT INTO work_link_dispute (work_id, other_work, kind, reported_by, note)
			VALUES (?, ?, ?, ?, ?)
			ON CONFLICT DO NOTHING
			""".trimIndent(),
		).use { statement ->
			statement.setLong(1, workId)
			otherWorkId?.let { statement.setLong(2, it) } ?: statement.setNull(2, java.sql.Types.BIGINT)
			statement.setString(3, kind)
			statement.setString(4, reportedBy)
			statement.setString(5, note)
			statement.executeUpdate() > 0
		}
	}

	fun resolveDispute(id: Long, moderatorId: String): Boolean = dataSource.connection.use { connection ->
		resolveDispute(connection, id, moderatorId)
	}

	fun resolveDispute(connection: Connection, id: Long, moderatorId: String): Boolean =
		connection.prepareStatement(
			"UPDATE work_link_dispute SET resolved_at = now(), resolved_by = ? " +
				"WHERE id = ? AND resolved_at IS NULL",
		).use { statement ->
			statement.setString(1, moderatorId)
			statement.setLong(2, id)
			statement.executeUpdate() > 0
		}

	fun reviewBrigadeFlag(id: Long): Boolean = dataSource.connection.use { connection ->
		reviewBrigadeFlag(connection, id)
	}

	fun reviewBrigadeFlag(connection: Connection, id: Long): Boolean =
		connection.prepareStatement(
			"UPDATE rating_brigade_flag SET reviewed_at = now() WHERE id = ? AND reviewed_at IS NULL",
		).use { statement ->
			statement.setLong(1, id)
			statement.executeUpdate() > 0
		}

	/** Queue sizes for the panel's nav, in one round trip rather than five. */
	fun counts(hours: Int, minDislikes: Int): Map<String, Int> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT
				(SELECT count(*) FROM comment
				 WHERE created_at > now() - make_interval(hours => ?) AND down >= ? AND state <> 2
				   AND dismissed_at IS NULL),
				(SELECT count(*) FROM ban_evasion_flag WHERE reviewed_at IS NULL),
				(SELECT count(*) FROM rating_brigade_flag WHERE reviewed_at IS NULL),
				(SELECT count(*) FROM work_link_dispute WHERE resolved_at IS NULL),
				(SELECT count(*) FROM comment WHERE flagged_rule IS NOT NULL AND state <> 2
				   AND dismissed_at IS NULL),
				(SELECT count(*) FROM filter_block WHERE reviewed_at IS NULL)
			""".trimIndent(),
		).use { statement ->
			statement.setInt(1, hours)
			statement.setInt(2, minDislikes)
			statement.executeQuery().use { rows ->
				rows.next()
				mapOf(
					"disliked" to rows.getInt(1),
					"ban_evasion" to rows.getInt(2),
					"brigades" to rows.getInt(3),
					"disputes" to rows.getInt(4),
					"flagged" to rows.getInt(5),
					"blocked" to rows.getInt(6),
				)
			}
		}
	}

	private fun ResultSet.toQueuedComment() = QueuedComment(
		id = getLong("id"),
		workId = getLong("work_id"),
		workTitle = getString("canonical_title"),
		chapterId = getLong("work_chapter_id").takeUnless { wasNull() },
		body = getString("body"),
		lang = getString("lang"),
		state = getShort("state"),
		up = getInt("up"),
		down = getInt("down"),
		score = getDouble("score"),
		createdAt = getObject("created_at", OffsetDateTime::class.java),
		authorId = getString("user_id"),
		authorName = displayName(getString("nickname"), getString("user_id")),
		authorIsBanned = getBoolean("is_banned"),
		authorIsShadowbanned = getBoolean("is_shadowbanned"),
		authorRemovedCount = getInt("removed_count"),
		flaggedRule = getString("flagged_rule"),
	)

	/** Exactly the `nickname#1234` the app shows - a moderator has to be able to match the two up. */
	private fun displayName(nickname: String?, userId: String): String = Nicknames.display(nickname, userId)

	private companion object {
		val COMMENT_SELECT = """
			SELECT c.id, c.work_id, w.canonical_title, c.work_chapter_id, c.body, c.lang, c.state,
			       c.up, c.down, c.score, c.created_at, c.user_id, u.nickname, u.is_banned,
			       u.is_shadowbanned, c.flagged_rule,
			       (SELECT count(*) FROM comment r WHERE r.user_id = c.user_id AND r.state = 2)
			           AS removed_count
			FROM comment c
			JOIN work w ON w.id = c.work_id
			JOIN app_user u ON u.id = c.user_id
		""".trimIndent()
	}
}
