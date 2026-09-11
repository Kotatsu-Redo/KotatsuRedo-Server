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

class RatingRepository(private val dataSource: DataSource) {

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
			statement.executeQuery("SELECT COALESCE(AVG(value), 0) FROM rating").use {
				it.next()
				it.getDouble(1)
			}
		}
	}

	fun rawStats(workId: Long): Triple<Int, Double, List<Int>> = dataSource.connection.use { connection ->
		connection.prepareStatement("SELECT value FROM rating WHERE work_id = ?").use { statement ->
			statement.setLong(1, workId)
			statement.executeQuery().use { rows ->
				val histogram = MutableList(RatingScale.BUCKETS) { 0 }
				var total = 0
				var count = 0
				while (rows.next()) {
					val value = rows.getInt(1)
					total += value
					count++
					val bucket = RatingScale.histogramBucket(value) - 1
					histogram[bucket] = histogram[bucket] + 1
				}
				Triple(count, if (count == 0) 0.0 else total.toDouble() / count, histogram)
			}
		}
	}

	fun storeAggregate(aggregate: RatingAggregate) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO work_rating_agg (work_id, count, mean, bayesian, histogram, updated_at)
				VALUES (?, ?, ?, ?, ?, now())
				ON CONFLICT (work_id) DO UPDATE SET
					count = EXCLUDED.count, mean = EXCLUDED.mean, bayesian = EXCLUDED.bayesian,
					histogram = EXCLUDED.histogram, updated_at = now()
				""".trimIndent(),
			).use { statement ->
				statement.setLong(1, aggregate.workId)
				statement.setInt(2, aggregate.count)
				statement.setFloat(3, aggregate.mean.toFloat())
				statement.setFloat(4, aggregate.bayesian.toFloat())
				statement.setArray(5, connection.createArrayOf("integer", aggregate.histogram.toTypedArray()))
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
				WHERE r.work_id = ? AND r.created_at > now() - make_interval(hours => ?)
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
			"SELECT count(*) FROM rating WHERE work_id = ? AND created_at <= now() - make_interval(hours => ?)",
		).use { statement ->
			statement.setLong(1, workId)
			statement.setInt(2, hours)
			statement.executeQuery().use { it.next(); it.getInt(1) }
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
