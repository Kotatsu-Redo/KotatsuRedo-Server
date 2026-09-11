package io.kotatsuredo.server.comments

import java.sql.ResultSet
import java.sql.Statement
import java.time.OffsetDateTime
import javax.sql.DataSource

class CommentRepository(private val dataSource: DataSource) {

	fun insert(
		workId: Long,
		chapterId: Long?,
		userId: String,
		parentId: Long?,
		depth: Int,
		body: String,
		isSpoiler: Boolean,
		lang: String?,
		state: CommentState,
		flaggedRule: String? = null,
	): Long = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			INSERT INTO comment
				(work_id, origin_work_id, work_chapter_id, user_id, parent_id, depth, body,
				 is_spoiler, lang, state, flagged_rule)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""".trimIndent(),
			Statement.RETURN_GENERATED_KEYS,
		).use { statement ->
			statement.setLong(1, workId)
			statement.setLong(2, workId)
			chapterId?.let { statement.setLong(3, it) } ?: statement.setNull(3, java.sql.Types.BIGINT)
			statement.setString(4, userId)
			parentId?.let { statement.setLong(5, it) } ?: statement.setNull(5, java.sql.Types.BIGINT)
			statement.setInt(6, depth)
			statement.setString(7, body)
			statement.setBoolean(8, isSpoiler)
			statement.setString(9, lang)
			statement.setShort(10, state.code)
			statement.setString(11, flaggedRule)
			statement.executeUpdate()
			statement.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
		}
	}

	fun find(id: Long): Comment? = dataSource.connection.use { connection ->
		connection.prepareStatement("$SELECT_COLUMNS WHERE id = ?").use { statement ->
			statement.setLong(1, id)
			statement.executeQuery().use { if (it.next()) it.toComment() else null }
		}
	}

	/**
	 * A page of root comments.
	 *
	 * Paging over roots rather than over every row is what keeps a thread intact across a page
	 * boundary: score-ordering a flat list would scatter a reply three pages away from what it replies
	 * to.
	 *
	 * Shadowed comments are returned only to their own author, which is the entire mechanism - it has
	 * to hold on every read path or the shadowban leaks.
	 */
	fun listRoots(
		workId: Long,
		chapterId: Long?,
		viewerId: String,
		lang: String?,
		sortByScore: Boolean,
		limit: Int,
		offset: Int,
	): List<Comment> = dataSource.connection.use { connection ->
		val chapterClause = if (chapterId == null) "AND work_chapter_id IS NULL" else "AND work_chapter_id = ?"
		val langClause = if (lang == null) "" else "AND lang = ?"
		val order = if (sortByScore) "score DESC, created_at DESC" else "created_at DESC"
		connection.prepareStatement(
			"""
			$SELECT_COLUMNS
			WHERE work_id = ?
			  $chapterClause
			  $langClause
			  AND parent_id IS NULL
			  -- A removed root is still listed when something live hangs off it. Dropping it would
			  -- take the whole conversation underneath with it, which is the opposite of what a
			  -- tombstone is for; a removed root with nothing below it is just gone.
			  AND (
			    state <> ${CommentState.REMOVED.code}
			    OR EXISTS (
			      SELECT 1 FROM comment child
			      WHERE child.parent_id = comment.id AND child.state <> ${CommentState.REMOVED.code}
			    )
			  )
			  AND (state <> ${CommentState.SHADOWED.code} OR user_id = ?)
			ORDER BY $order
			LIMIT ? OFFSET ?
			""".trimIndent(),
		).use { statement ->
			var index = 1
			statement.setLong(index++, workId)
			chapterId?.let { statement.setLong(index++, it) }
			lang?.let { statement.setString(index++, it) }
			statement.setString(index++, viewerId)
			statement.setInt(index++, limit)
			statement.setInt(index, offset)
			statement.executeQuery().use { rows ->
				buildList { while (rows.next()) add(rows.toComment()) }
			}
		}
	}

	/**
	 * Everything hanging below the given roots, in one round trip.
	 *
	 * A removed comment is kept in the walk as a tombstone rather than pruned, because dropping it
	 * would take every reply underneath with it and silently delete other people's words. The service
	 * blanks its body before it reaches anyone.
	 */
	fun descendantsOf(rootIds: Collection<Long>, viewerId: String): List<Comment> {
		if (rootIds.isEmpty()) return emptyList()
		return dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				WITH RECURSIVE thread AS (
					SELECT ${COLUMNS.joinToString(", ") { "c.$it" }} FROM comment c
					WHERE c.parent_id = ANY (?)
					UNION ALL
					SELECT ${COLUMNS.joinToString(", ") { "child.$it" }} FROM comment child
					JOIN thread ON child.parent_id = thread.id
				)
				SELECT ${COLUMNS.joinToString(", ")} FROM thread
				WHERE state <> ${CommentState.SHADOWED.code} OR user_id = ?
				ORDER BY created_at
				""".trimIndent(),
			).use { statement ->
				statement.setArray(1, connection.createArrayOf("bigint", rootIds.toTypedArray()))
				statement.setString(2, viewerId)
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.toComment()) }
				}
			}
		}
	}

	/** Display names for a page of comments, in one query rather than one per author. */
	fun authorNames(userIds: Collection<String>): Map<String, String?> {
		if (userIds.isEmpty()) return emptyMap()
		return dataSource.connection.use { connection ->
			connection.prepareStatement("SELECT id, nickname FROM app_user WHERE id = ANY (?)")
				.use { statement ->
					statement.setArray(1, connection.createArrayOf("text", userIds.toTypedArray()))
					statement.executeQuery().use { rows ->
						buildMap<String, String?> { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
					}
				}
		}
	}

	/** This caller's votes across a page, in one query. */
	fun myVotes(commentIds: Collection<Long>, userId: String): Map<Long, Int> {
		if (commentIds.isEmpty()) return emptyMap()
		return dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT comment_id, value FROM comment_vote WHERE user_id = ? AND comment_id = ANY (?)",
			).use { statement ->
				statement.setString(1, userId)
				statement.setArray(2, connection.createArrayOf("bigint", commentIds.toTypedArray()))
				statement.executeQuery().use { rows ->
					buildMap { while (rows.next()) put(rows.getLong(1), rows.getInt(2)) }
				}
			}
		}
	}

	fun countPerLanguage(workId: Long, chapterId: Long?): Map<String, Int> =
		dataSource.connection.use { connection ->
			val chapterClause = if (chapterId == null) "AND work_chapter_id IS NULL" else "AND work_chapter_id = ?"
			connection.prepareStatement(
				"""
				SELECT COALESCE(lang, 'und') AS lang, count(*) FROM comment
				WHERE work_id = ? $chapterClause AND state = ${CommentState.VISIBLE.code}
				GROUP BY 1
				""".trimIndent(),
			).use { statement ->
				statement.setLong(1, workId)
				chapterId?.let { statement.setLong(2, it) }
				statement.executeQuery().use { rows ->
					buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) }
				}
			}
		}

	/**
	 * The ancestor chain, nearest first.
	 *
	 * Chains are short by construction (the round cap sees to that), so a walk up `parent_id` beats a
	 * recursive CTE for both clarity and cost.
	 */
	fun ancestors(commentId: Long, limit: Int = 16): List<Comment> {
		val chain = mutableListOf<Comment>()
		var current = find(commentId)
		while (current != null && chain.size < limit) {
			chain.add(current)
			current = current.parentId?.let(::find)
		}
		return chain
	}

	fun update(id: Long, body: String, lang: String?): Boolean = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"UPDATE comment SET body = ?, lang = ?, edited_at = now() WHERE id = ?",
		).use { statement ->
			statement.setString(1, body)
			statement.setString(2, lang)
			statement.setLong(3, id)
			statement.executeUpdate() > 0
		}
	}

	/**
	 * Removal blanks the text in place. The row survives as a tombstone so replies underneath keep
	 * their thread, but the words themselves are gone from the database - "deleted" that leaves the
	 * text sitting in a column is not deleted.
	 *
	 * A moderator removal therefore has to snapshot what it removed into its own audit record (M4b);
	 * this is not the place to keep it.
	 */
	/** Records that a `watch` term matched. Visible to the panel only. */
	fun flag(id: Long, term: String): Boolean = dataSource.connection.use { connection ->
		connection.prepareStatement("UPDATE comment SET flagged_rule = ? WHERE id = ?").use { statement ->
			statement.setString(1, term)
			statement.setLong(2, id)
			statement.executeUpdate() > 0
		}
	}

	fun clearFlag(id: Long): Boolean = dataSource.connection.use { connection ->
		connection.prepareStatement("UPDATE comment SET flagged_rule = NULL WHERE id = ?").use { statement ->
			statement.setLong(1, id)
			statement.executeUpdate() > 0
		}
	}

	fun setState(id: Long, state: CommentState): Boolean = dataSource.connection.use { connection ->
		val removed = state == CommentState.REMOVED
		connection.prepareStatement(
			"""
			UPDATE comment SET
				state = ?,
				body = CASE WHEN ? THEN '' ELSE body END,
				deleted_at = CASE WHEN ? THEN now() ELSE deleted_at END
			WHERE id = ?
			""".trimIndent(),
		).use { statement ->
			statement.setShort(1, state.code)
			statement.setBoolean(2, removed)
			statement.setBoolean(3, removed)
			statement.setLong(4, id)
			statement.executeUpdate() > 0
		}
	}

	/**
	 * Puts a removed comment back, text and all.
	 *
	 * The text comes from the moderation audit snapshot rather than from this table, because removal
	 * blanked it here - which is the point of blanking it, and the reason a restore is a moderation
	 * action rather than a flag flip.
	 */
	fun restore(id: Long, body: String, state: CommentState): Boolean =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"UPDATE comment SET state = ?, body = ?, deleted_at = NULL WHERE id = ?",
			).use { statement ->
				statement.setShort(1, state.code)
				statement.setString(2, body)
				statement.setLong(3, id)
				statement.executeUpdate() > 0
			}
		}

	/**
	 * Clears a banned user's comments without dropping the rows: hard deletion would orphan every
	 * reply underneath and tear holes in threads other people were part of.
	 */
	fun tombstoneAllBy(userId: String): Int = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"UPDATE comment SET state = ?, body = '', deleted_at = now() WHERE user_id = ? AND state <> ?",
		).use { statement ->
			statement.setShort(1, CommentState.REMOVED.code)
			statement.setString(2, userId)
			statement.setShort(3, CommentState.REMOVED.code)
			statement.executeUpdate()
		}
	}

	// -- votes -----------------------------------------------------------------------------------

	fun setVote(commentId: Long, userId: String, value: Int) {
		dataSource.connection.use { connection ->
			if (value == 0) {
				connection.prepareStatement(
					"DELETE FROM comment_vote WHERE comment_id = ? AND user_id = ?",
				).use { statement ->
					statement.setLong(1, commentId)
					statement.setString(2, userId)
					statement.executeUpdate()
				}
			} else {
				connection.prepareStatement(
					"""
					INSERT INTO comment_vote (comment_id, user_id, value) VALUES (?, ?, ?)
					ON CONFLICT (comment_id, user_id) DO UPDATE SET value = EXCLUDED.value
					""".trimIndent(),
				).use { statement ->
					statement.setLong(1, commentId)
					statement.setString(2, userId)
					statement.setInt(3, value)
					statement.executeUpdate()
				}
			}
		}
	}

	fun myVote(commentId: Long, userId: String): Int = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"SELECT value FROM comment_vote WHERE comment_id = ? AND user_id = ?",
		).use { statement ->
			statement.setLong(1, commentId)
			statement.setString(2, userId)
			statement.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
		}
	}

	/**
	 * Recounts from the votes table rather than incrementing, so a double submission or a changed
	 * vote cannot drift the totals.
	 *
	 * Votes cast by shadowbanned users are excluded here - that is where "their votes stop counting"
	 * actually happens.
	 */
	fun recountVotes(commentId: Long): Triple<Int, Int, Double> = dataSource.connection.use { connection ->
		val (up, down) = connection.prepareStatement(
			"""
			SELECT
				count(*) FILTER (WHERE v.value = 1)  AS up,
				count(*) FILTER (WHERE v.value = -1) AS down
			FROM comment_vote v
			JOIN app_user u ON u.id = v.user_id
			WHERE v.comment_id = ? AND NOT u.is_shadowbanned
			""".trimIndent(),
		).use { statement ->
			statement.setLong(1, commentId)
			statement.executeQuery().use { it.next(); it.getInt(1) to it.getInt(2) }
		}

		val score = io.kotatsuredo.server.scoring.Scoring.wilsonLowerBound(
			successes = up.toDouble(),
			total = (up + down).toDouble(),
		)
		connection.prepareStatement("UPDATE comment SET up = ?, down = ?, score = ? WHERE id = ?")
			.use { statement ->
				statement.setInt(1, up)
				statement.setInt(2, down)
				statement.setFloat(3, score.toFloat())
				statement.setLong(4, commentId)
				statement.executeUpdate()
			}
		Triple(up, down, score)
	}

	// -- data export -----------------------------------------------------------------------------

	/**
	 * Everything this user has written, including what is shadowed or removed.
	 *
	 * Unlike every other read path this does not hide anything, because it is the user asking for
	 * their own data: a copy that quietly omits the comments a moderator removed is not a copy.
	 */
	fun allBy(userId: String): List<Comment> = dataSource.connection.use { connection ->
		connection.prepareStatement("$SELECT_COLUMNS WHERE user_id = ? ORDER BY created_at").use { statement ->
			statement.setString(1, userId)
			statement.executeQuery().use { rows ->
				buildList { while (rows.next()) add(rows.toComment()) }
			}
		}
	}

	/** @return (comment id, value, cast at) for every vote this user has cast. */
	fun votesBy(userId: String): List<Triple<Long, Int, OffsetDateTime>> =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT comment_id, value, created_at FROM comment_vote WHERE user_id = ? ORDER BY created_at",
			).use { statement ->
				statement.setString(1, userId)
				statement.executeQuery().use { rows ->
					buildList {
						while (rows.next()) {
							add(
								Triple(
									rows.getLong(1),
									rows.getInt(2),
									rows.getObject(3, OffsetDateTime::class.java),
								),
							)
						}
					}
				}
			}
		}

	// -- notifications ---------------------------------------------------------------------------

	/**
	 * Replies to this user's comments, newer than [since].
	 *
	 * Three exclusions, each a bug if missed: a **shadowed** reply must not notify (it is invisible to
	 * everyone but its author, and notifying would give the shadowban away), a reply whose **parent
	 * was removed** has nothing to open, and nobody is notified of their own reply.
	 */
	fun notifications(userId: String, since: OffsetDateTime, limit: Int): List<Comment> =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT ${COLUMNS.joinToString(", ") { "c.$it" }}
				FROM comment c
				JOIN comment parent ON parent.id = c.parent_id
				WHERE parent.user_id = ?
				  AND c.user_id <> ?
				  AND c.created_at > ?
				  AND c.state = ${CommentState.VISIBLE.code}
				  AND parent.state <> ${CommentState.REMOVED.code}
				ORDER BY c.created_at DESC
				LIMIT ?
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, userId)
				statement.setString(2, userId)
				statement.setObject(3, since)
				statement.setInt(4, limit)
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.toComment()) }
				}
			}
		}

	fun countForWork(workId: Long, chapterId: Long?): Int = dataSource.connection.use { connection ->
		val chapterClause = if (chapterId == null) "AND work_chapter_id IS NULL" else "AND work_chapter_id = ?"
		connection.prepareStatement(
			"SELECT count(*) FROM comment WHERE work_id = ? $chapterClause AND state = ${CommentState.VISIBLE.code}",
		).use { statement ->
			statement.setLong(1, workId)
			chapterId?.let { statement.setLong(2, it) }
			statement.executeQuery().use { it.next(); it.getInt(1) }
		}
	}

	private fun ResultSet.toComment() = Comment(
		id = getLong("id"),
		workId = getLong("work_id"),
		chapterId = getLong("work_chapter_id").takeUnless { wasNull() },
		userId = getString("user_id"),
		parentId = getLong("parent_id").takeUnless { wasNull() },
		depth = getInt("depth"),
		body = getString("body"),
		isSpoiler = getBoolean("is_spoiler"),
		lang = getString("lang"),
		createdAt = getObject("created_at", OffsetDateTime::class.java),
		editedAt = getObject("edited_at", OffsetDateTime::class.java),
		state = CommentState.of(getShort("state")),
		score = getDouble("score"),
		up = getInt("up"),
		down = getInt("down"),
		flaggedRule = getString("flagged_rule"),
	)

	private companion object {
		val COLUMNS = listOf(
			"id", "work_id", "work_chapter_id", "user_id", "parent_id", "depth", "body",
			"is_spoiler", "lang", "created_at", "edited_at", "state", "score", "up", "down",
			"flagged_rule",
		)
		val SELECT_COLUMNS = "SELECT ${COLUMNS.joinToString(", ")} FROM comment"
	}
}
