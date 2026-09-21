package io.kotatsuredo.server.ratings

import javax.sql.DataSource

/**
 * Values are half-stars: 2 is one star, 10 is five. [stars] converts for display, [histogramBucket]
 * for the distribution.
 */
object RatingScale {
	const val MIN = 1
	const val MAX = 10
	const val BUCKETS = 5

	fun isValid(value: Int) = value in MIN..MAX

	fun stars(value: Int): Double = value / 2.0

	/** 1..2 -> star 1, 3..4 -> star 2, ... so a half-star rounds up into its star. */
	fun histogramBucket(value: Int): Int = ((value + 1) / 2).coerceIn(1, BUCKETS)
}

/** One row of a user's own rating history, as it appears in their data export. */
data class ExportedRating(
	val workId: Long,
	val value: Int,
	val createdAt: java.time.OffsetDateTime,
	val updatedAt: java.time.OffsetDateTime,
)

data class RatingAggregate(
	val workId: Long,
	val count: Int,
	val mean: Double,
	val bayesian: Double,
	val histogram: List<Int>,
)

data class BrigadeStats(
	val recentCount: Int,
	/** Every rating from before the window, however old. */
	val olderCount: Int,
	/** Mean of those older ratings: the direction the work's existing audience leans. */
	val olderMean: Double,
	/** Older ratings inside the baseline period, the numerator of the per-day rate. */
	val baselineCount: Int,
	/** Days the baseline ratings actually cover, from the oldest of them to the window start. */
	val baselineSpanDays: Double,
	val lowShare: Double,
	val highShare: Double,
	val newUserShare: Double,
)

class RatingRepository(private val dataSource: DataSource) {
	/** Writes a rating and its denormalised aggregate with one checkout and transaction. */
	fun setAndAggregate(workId: Long, userId: String, value: Int): RatingAggregate =
		aggregateTransaction(workId, userId, value) { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO rating (work_id, origin_work_id, user_id, value) VALUES (?, ?, ?, ?)
				ON CONFLICT (work_id, user_id) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
				""".trimIndent(),
			).use {
				it.setLong(1, workId)
				it.setLong(2, workId)
				it.setString(3, userId)
				it.setInt(4, value)
				it.executeUpdate()
			}
		}

	/** Deletes one rating and repairs the aggregate in the same per-work transaction. */
	fun deleteAndAggregate(workId: Long, userId: String): Pair<Boolean, RatingAggregate> {
		var deleted = false
		val aggregate = aggregateTransaction(workId, userId, null) { connection ->
			deleted = connection.prepareStatement("DELETE FROM rating WHERE work_id = ? AND user_id = ?").use {
				it.setLong(1, workId)
				it.setString(2, userId)
				it.executeUpdate() > 0
			}
		}
		return deleted to aggregate
	}

	fun set(workId: Long, userId: String, value: Int) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO rating (work_id, origin_work_id, user_id, value) VALUES (?, ?, ?, ?)
				ON CONFLICT (work_id, user_id) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
				""".trimIndent(),
			).use { statement ->
				// origin_work_id is set once and never changes, so an unmerge knows which ratings to
				// take back with it (M4b).
				statement.setLong(1, workId)
				statement.setLong(2, workId)
				statement.setString(3, userId)
				statement.setInt(4, value)
				statement.executeUpdate()
			}
		}
	}

	fun delete(workId: Long, userId: String): Boolean = dataSource.connection.use { connection ->
		connection.prepareStatement("DELETE FROM rating WHERE work_id = ? AND user_id = ?").use { statement ->
			statement.setLong(1, workId)
			statement.setString(2, userId)
			statement.executeUpdate() > 0
		}
	}

	/**
	 * The works this user has rated.
	 *
	 * Collected before an account is erased: the rating rows cascade away with it, but
	 * `work_rating_agg` is denormalised and would go on reporting the old count and average for as
	 * long as nobody else rated the work.
	 */
	fun workIdsRatedBy(userId: String): List<Long> = dataSource.connection.use { connection ->
		connection.prepareStatement("SELECT work_id FROM rating WHERE user_id = ?").use { statement ->
			statement.setString(1, userId)
			statement.executeQuery().use { rows ->
				buildList { while (rows.next()) add(rows.getLong(1)) }
			}
		}
	}

	/** Every rating this user has given, for the data export. */
	fun allBy(userId: String): List<ExportedRating> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"SELECT work_id, value, created_at, updated_at FROM rating WHERE user_id = ? ORDER BY created_at",
		).use { statement ->
			statement.setString(1, userId)
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(
							ExportedRating(
								workId = rows.getLong(1),
								value = rows.getInt(2),
								createdAt = rows.getObject(3, java.time.OffsetDateTime::class.java),
								updatedAt = rows.getObject(4, java.time.OffsetDateTime::class.java),
							),
						)
					}
				}
			}
		}
	}

	fun allByPage(userId: String, afterWorkId: Long, limit: Int): List<ExportedRating> =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT work_id, value, created_at, updated_at FROM rating " +
					"WHERE user_id = ? AND work_id > ? ORDER BY work_id LIMIT ?",
			).use { statement ->
				statement.setString(1, userId)
				statement.setLong(2, afterWorkId)
				statement.setInt(3, limit)
				statement.executeQuery().use { rows ->
					buildList {
						while (rows.next()) add(
							ExportedRating(
								rows.getLong(1), rows.getInt(2),
								rows.getObject(3, java.time.OffsetDateTime::class.java),
								rows.getObject(4, java.time.OffsetDateTime::class.java),
							),
						)
					}
				}
			}
		}

	fun find(workId: Long, userId: String): Int? = dataSource.connection.use { connection ->
		connection.prepareStatement("SELECT value FROM rating WHERE work_id = ? AND user_id = ?")
			.use { statement ->
				statement.setLong(1, workId)
				statement.setString(2, userId)
				statement.executeQuery().use { if (it.next()) it.getInt(1) else null }
			}
	}

	/** The global mean across every rating, which is the prior a Bayesian average pulls toward. */
	fun globalMean(): Double = dataSource.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery(
				"SELECT CASE WHEN sum(count) = 0 THEN 0 ELSE sum(value_sum)::float8 / sum(count) END " +
					"FROM rating_global_shard",
			).use {
				it.next()
				it.getDouble(1)
			}
		}
	}

	fun rawStats(workId: Long): Triple<Int, Double, List<Int>> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT count(*), COALESCE(avg(value), 0),
			       count(*) FILTER (WHERE value BETWEEN 1 AND 2),
			       count(*) FILTER (WHERE value BETWEEN 3 AND 4),
			       count(*) FILTER (WHERE value BETWEEN 5 AND 6),
			       count(*) FILTER (WHERE value BETWEEN 7 AND 8),
			       count(*) FILTER (WHERE value BETWEEN 9 AND 10)
			FROM rating WHERE work_id = ?
			""".trimIndent(),
		).use { statement ->
			statement.setLong(1, workId)
			statement.executeQuery().use { rows ->
				rows.next()
				Triple(rows.getInt(1), rows.getDouble(2), (3..7).map(rows::getInt))
			}
		}
	}

	fun storeAggregate(aggregate: RatingAggregate) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO work_rating_agg (work_id, count, value_sum, mean, bayesian, histogram, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (work_id) DO UPDATE SET
					count = EXCLUDED.count, value_sum = EXCLUDED.value_sum,
					mean = EXCLUDED.mean, bayesian = EXCLUDED.bayesian,
					histogram = EXCLUDED.histogram, updated_at = now()
				""".trimIndent(),
			).use { statement ->
				statement.setLong(1, aggregate.workId)
				statement.setInt(2, aggregate.count)
				statement.setLong(3, kotlin.math.round(aggregate.mean * aggregate.count).toLong())
				statement.setFloat(4, aggregate.mean.toFloat())
				statement.setFloat(5, aggregate.bayesian.toFloat())
				statement.setArray(6, connection.createArrayOf("integer", aggregate.histogram.toTypedArray()))
				statement.executeUpdate()
			}
		}
	}

	fun aggregate(workId: Long): RatingAggregate? = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"SELECT count, mean, bayesian, histogram FROM work_rating_agg WHERE work_id = ?",
		).use { statement ->
			statement.setLong(1, workId)
			statement.executeQuery().use { rows ->
				if (!rows.next()) return@use null
				@Suppress("UNCHECKED_CAST")
				val histogram = (rows.getArray(4).array as Array<Integer>).map { it.toInt() }
				RatingAggregate(workId, rows.getInt(1), rows.getDouble(2), rows.getDouble(3), histogram)
			}
		}
	}

	private fun aggregateTransaction(
		workId: Long,
		userId: String,
		newValue: Int?,
		mutation: (java.sql.Connection) -> Unit,
	): RatingAggregate = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			// Account erasure takes FOR UPDATE on this row before taking work locks. Matching that
			// order prevents a rating write and deletion from deadlocking or publishing a stale total.
			connection.prepareStatement("SELECT 1 FROM app_user WHERE id = ? FOR KEY SHARE").use {
				it.setString(1, userId)
				it.executeQuery().use { rows ->
					if (!rows.next()) throw IllegalStateException("rating user does not exist")
				}
			}
			connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
				it.setLong(1, workId)
				it.execute()
			}
			val oldValue = connection.prepareStatement(
				"SELECT value FROM rating WHERE work_id = ? AND user_id = ?",
			).use {
				it.setLong(1, workId)
				it.setString(2, userId)
				it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
			}
			val stored = connection.prepareStatement(
				"SELECT count, value_sum, histogram FROM work_rating_agg WHERE work_id = ? FOR UPDATE",
			).use {
				it.setLong(1, workId)
				it.executeQuery().use { rows ->
					if (!rows.next()) {
						Triple(0, 0L, MutableList(RatingScale.BUCKETS) { 0 })
					} else {
						@Suppress("UNCHECKED_CAST")
						val histogram = (rows.getArray(3).array as Array<Integer>).map { value -> value.toInt() }
						Triple(rows.getInt(1), rows.getLong(2), histogram.toMutableList())
					}
				}
			}
			mutation(connection)
			val count = stored.first + when {
				oldValue == null && newValue != null -> 1
				oldValue != null && newValue == null -> -1
				else -> 0
			}
			val valueSum = stored.second + (newValue ?: 0) - (oldValue ?: 0)
			val histogram = stored.third
			oldValue?.let { histogram[RatingScale.histogramBucket(it) - 1]-- }
			newValue?.let { histogram[RatingScale.histogramBucket(it) - 1]++ }
			check(count >= 0 && valueSum >= 0 && histogram.all { it >= 0 }) {
				"rating aggregate drifted below zero"
			}
			val mean = if (count == 0) 0.0 else valueSum.toDouble() / count
			val globalMean = connection.createStatement().use { statement ->
				statement.executeQuery(
					"SELECT CASE WHEN sum(count) = 0 THEN 0 ELSE sum(value_sum)::float8 / sum(count) END " +
						"FROM rating_global_shard",
				).use { rows -> rows.next(); rows.getDouble(1) }
			}
			val aggregate = RatingAggregate(
				workId, count, mean, RatingService.bayesianAverage(count, mean, globalMean), histogram,
			)
			connection.prepareStatement(
				"""
				INSERT INTO work_rating_agg (work_id, count, value_sum, mean, bayesian, histogram, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (work_id) DO UPDATE SET count = EXCLUDED.count, value_sum = EXCLUDED.value_sum,
					mean = EXCLUDED.mean,
					bayesian = EXCLUDED.bayesian, histogram = EXCLUDED.histogram, updated_at = now()
				""".trimIndent(),
			).use {
				it.setLong(1, aggregate.workId)
				it.setInt(2, aggregate.count)
				it.setLong(3, valueSum)
				it.setDouble(4, aggregate.mean)
				it.setDouble(5, aggregate.bayesian)
				it.setArray(6, connection.createArrayOf("integer", aggregate.histogram.toTypedArray()))
				it.executeUpdate()
			}
			connection.commit()
			aggregate
		} catch (error: Exception) {
			connection.rollback()
			throw error
		} finally {
			connection.autoCommit = true
		}
	}

	/**
	 * Recent ratings with the rater's trust tier, for brigade detection. A spike alone is a manga
	 * getting popular; a spike that is all extremes and all new accounts is coordination.
	 */
	fun recentRatings(workId: Long, hours: Int): List<Pair<Int, Int>> =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT r.value, t.tier
				FROM rating r
				JOIN user_trust t ON t.user_id = r.user_id
				WHERE r.work_id = ? AND r.updated_at > now() - make_interval(hours => ?)
				""".trimIndent(),
			).use { statement ->
				statement.setLong(1, workId)
				statement.setInt(2, hours)
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.getInt(1) to rows.getInt(2)) }
				}
			}
		}

	fun ratingsBefore(workId: Long, hours: Int): Int = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"SELECT count(*) FROM rating WHERE work_id = ? AND updated_at <= now() - make_interval(hours => ?)",
		).use { statement ->
			statement.setLong(1, workId)
			statement.setInt(2, hours)
			statement.executeQuery().use { it.next(); it.getInt(1) }
		}
	}

	/**
	 * Only the recent side joins `user_trust`: the view counts active days per user, and a popular
	 * work's full history is far larger than one day's window.
	 */
	fun brigadeStats(
		workId: Long,
		hours: Int,
		baselineDays: Int,
		extremeLow: Int,
		extremeHigh: Int,
	): BrigadeStats =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				WITH cutoff AS (SELECT now() - make_interval(hours => ?) AS at),
				older AS (
					SELECT count(*) AS n,
						COALESCE(avg(r.value), 0) AS mean,
						count(*) FILTER (WHERE r.updated_at > c.at - make_interval(days => ?)) AS in_baseline,
						min(r.updated_at) FILTER (WHERE r.updated_at > c.at - make_interval(days => ?)) AS since
					FROM rating r, cutoff c
					WHERE r.work_id = ? AND r.updated_at <= c.at
				),
				recent AS (
					SELECT count(*) AS n,
						COALESCE(avg(CASE WHEN r.value <= ? THEN 1.0 ELSE 0.0 END), 0) AS low,
						COALESCE(avg(CASE WHEN r.value >= ? THEN 1.0 ELSE 0.0 END), 0) AS high,
						COALESCE(avg(CASE WHEN t.tier = 0 THEN 1.0 ELSE 0.0 END), 0) AS fresh
					FROM rating r
					JOIN user_trust t ON t.user_id = r.user_id, cutoff c
					WHERE r.work_id = ? AND r.updated_at > c.at
				)
				SELECT recent.n, older.n, older.mean, older.in_baseline,
					COALESCE(EXTRACT(EPOCH FROM (cutoff.at - older.since)) / 86400.0, 0),
					recent.low, recent.high, recent.fresh
				FROM cutoff, older, recent
				""".trimIndent(),
			).use { statement ->
				statement.setInt(1, hours)
				statement.setInt(2, baselineDays)
				statement.setInt(3, baselineDays)
				statement.setLong(4, workId)
				statement.setInt(5, extremeLow)
				statement.setInt(6, extremeHigh)
				statement.setLong(7, workId)
				statement.executeQuery().use { rows ->
					rows.next()
					BrigadeStats(
						recentCount = rows.getInt(1),
						olderCount = rows.getInt(2),
						olderMean = rows.getDouble(3),
						baselineCount = rows.getInt(4),
						baselineSpanDays = rows.getDouble(5),
						lowShare = rows.getDouble(6),
						highShare = rows.getDouble(7),
						newUserShare = rows.getDouble(8),
					)
				}
			}
		}

	fun flagBrigade(
		workId: Long,
		recentCount: Int,
		baseline: Double,
		extremeShare: Double,
		newUserShare: Double,
	) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO rating_brigade_flag
					(work_id, recent_count, baseline, extreme_share, new_user_share)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (work_id) WHERE reviewed_at IS NULL DO UPDATE SET
					detected_at = now(),
					recent_count = EXCLUDED.recent_count,
					baseline = EXCLUDED.baseline,
					extreme_share = EXCLUDED.extreme_share,
					new_user_share = EXCLUDED.new_user_share
				""".trimIndent(),
			).use { statement ->
				statement.setLong(1, workId)
				statement.setInt(2, recentCount)
				statement.setFloat(3, baseline.toFloat())
				statement.setFloat(4, extremeShare.toFloat())
				statement.setFloat(5, newUserShare.toFloat())
				statement.executeUpdate()
			}
		}
	}

	fun unreviewedFlags(): Int = dataSource.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery("SELECT count(*) FROM rating_brigade_flag WHERE reviewed_at IS NULL")
				.use { it.next(); it.getInt(1) }
		}
	}
}
