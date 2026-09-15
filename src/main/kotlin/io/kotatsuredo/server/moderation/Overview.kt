package io.kotatsuredo.server.moderation

import javax.sql.DataSource

/**
 * Everything the panel's home page shows, in one query round.
 *
 * One call rather than a dozen because a dashboard that fires twelve requests spends its first
 * second visibly assembling itself, and because a moderator opening the panel wants one answer -
 * *is anything wrong* - before they want any of the detail.
 */
data class Overview(
	val totals: Totals,
	val today: Today,
	val queues: Map<String, Int>,
	val series: List<Day>,
	val topRules: List<RuleLoad>,
	val languages: List<LanguageLoad>,
) {

	data class Totals(
		val users: Int,
		val comments: Int,
		val removed: Int,
		val ratings: Int,
		val works: Int,
		val moderators: Int,
	)

	/** The last 24 hours, which is the window a moderator checking in actually cares about. */
	data class Today(
		val comments: Int,
		val users: Int,
		val ratings: Int,
		val blocked: Int,
		val actions: Int,
	)

	data class Day(val day: String, val comments: Int, val users: Int)

	/** A rule and how it is behaving: lots of blocks is fine, lots of *disputed* blocks is not. */
	data class RuleLoad(val term: String, val tier: String, val blocks: Int, val disputed: Int)

	data class LanguageLoad(val lang: String, val comments: Int, val blocked: Int)
}

class OverviewRepository(private val dataSource: DataSource) {
	@Volatile
	private var cached: Pair<Long, Overview>? = null

	fun load(queues: Map<String, Int>): Overview {
		val now = System.nanoTime()
		cached?.takeIf { now - it.first < CACHE_NANOS }?.let { return it.second.copy(queues = queues) }
		return synchronized(this) {
			cached?.takeIf { now - it.first < CACHE_NANOS }?.let { return@synchronized it.second.copy(queues = queues) }
			val loaded = loadFresh(queues)
			cached = now to loaded
			loaded
		}
	}

	private fun loadFresh(queues: Map<String, Int>): Overview = dataSource.connection.use { connection ->
		val totals = connection.prepareStatement(
			"""
			SELECT
				(SELECT count(*) FROM app_user),
				(SELECT count(*) FROM comment WHERE state <> 2),
				(SELECT count(*) FROM comment WHERE state = 2),
				(SELECT count(*) FROM rating),
				(SELECT count(*) FROM work WHERE merged_into IS NULL),
				(SELECT count(*) FROM moderator WHERE NOT is_disabled)
			""".trimIndent(),
		).use { statement ->
			statement.executeQuery().use { rows ->
				rows.next()
				Overview.Totals(
					users = rows.getInt(1),
					comments = rows.getInt(2),
					removed = rows.getInt(3),
					ratings = rows.getInt(4),
					works = rows.getInt(5),
					moderators = rows.getInt(6),
				)
			}
		}

		val today = connection.prepareStatement(
			"""
			SELECT
				(SELECT count(*) FROM comment WHERE created_at > now() - interval '24 hours'),
				(SELECT count(*) FROM app_user WHERE created_at > now() - interval '24 hours'),
				(SELECT count(*) FROM rating WHERE created_at > now() - interval '24 hours'),
				(SELECT count(*) FROM filter_block WHERE created_at > now() - interval '24 hours'),
				(SELECT count(*) FROM mod_action WHERE created_at > now() - interval '24 hours')
			""".trimIndent(),
		).use { statement ->
			statement.executeQuery().use { rows ->
				rows.next()
				Overview.Today(
					comments = rows.getInt(1),
					users = rows.getInt(2),
					ratings = rows.getInt(3),
					blocked = rows.getInt(4),
					actions = rows.getInt(5),
				)
			}
		}

		// Fourteen days, zero-filled from a generated series so a quiet day is a gap in the line
		// rather than a missing point that the chart would otherwise draw straight through.
		val series = connection.prepareStatement(
			"""
			WITH days AS (
				SELECT generate_series(current_date - 13, current_date, interval '1 day')::date AS day
			), comments AS (
				SELECT created_at::date AS day, count(*) AS count
				FROM comment
				WHERE created_at >= current_date - 13 AND created_at < current_date + 1
				GROUP BY 1
			), users AS (
				SELECT created_at::date AS day, count(*) AS count
				FROM app_user
				WHERE created_at >= current_date - 13 AND created_at < current_date + 1
				GROUP BY 1
			)
			SELECT to_char(d.day, 'YYYY-MM-DD'), coalesce(c.count, 0), coalesce(u.count, 0)
			FROM days d
			LEFT JOIN comments c USING (day)
			LEFT JOIN users u USING (day)
			ORDER BY d.day
			""".trimIndent(),
		).use { statement ->
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(Overview.Day(rows.getString(1), rows.getInt(2), rows.getInt(3)))
					}
				}
			}
		}

		val topRules = connection.prepareStatement(
			"""
			-- Straight off filter_block, which keeps its own copy of the term and tier. Joining the
			-- rule would lose every block whose rule a moderator has since deleted, which is exactly
			-- the rule you would want to see in this list.
			SELECT b.term, b.tier, count(*), count(b.disputed_at)
			FROM filter_block b
			WHERE b.created_at > now() - interval '7 days'
			GROUP BY b.term, b.tier
			ORDER BY count(*) DESC
			LIMIT 5
			""".trimIndent(),
		).use { statement ->
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(
							Overview.RuleLoad(
								term = rows.getString(1),
								tier = tierName(rows.getInt(2)),
								blocks = rows.getInt(3),
								disputed = rows.getInt(4),
							),
						)
					}
				}
			}
		}

		val languages = connection.prepareStatement(
			"""
			-- Two aggregates joined rather than one correlated subquery: the subquery would have to
			-- reference the ungrouped column it is grouping by, which Postgres refuses outright.
			WITH visible AS (
				SELECT coalesce(lang, 'und') AS lang, count(*) AS comments
				FROM comment WHERE state <> 2 GROUP BY 1
			), blocked AS (
				SELECT coalesce(lang, 'und') AS lang, count(*) AS blocked
				FROM filter_block GROUP BY 1
			)
			SELECT v.lang, v.comments, coalesce(b.blocked, 0)
			FROM visible v LEFT JOIN blocked b ON b.lang = v.lang
			ORDER BY v.comments DESC
			LIMIT 8
			""".trimIndent(),
		).use { statement ->
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(Overview.LanguageLoad(rows.getString(1), rows.getInt(2), rows.getInt(3)))
					}
				}
			}
		}

		Overview(totals, today, queues, series, topRules, languages)
	}

	private fun tierName(code: Int): String = when (code) {
		0 -> "severe"
		1 -> "profanity"
		else -> "watch"
	}

	private companion object {
		const val CACHE_NANOS = 30_000_000_000L
	}
}
