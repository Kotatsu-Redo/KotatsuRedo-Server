package io.kotatsuredo.server.scoring

import java.sql.Types
import java.time.OffsetDateTime
import javax.sql.DataSource

/** One source in one region, after decay has been applied but before scoring. */
data class SourceAggregate(
	val source: String,
	val region: String,
	val okWeighted: Double,
	val failWeighted: Double,
	val emptyWeighted: Double,
	val cfBlockedWeighted: Double,
	val latencyP50Ms: Double,
	val reporterWeight: Double,
	val sampleSize: Int,
)

data class SourceScore(
	val source: String,
	val region: String,
	val stability: Double,
	val popularity: Double,
	val composite: Double,
	val sampleSize: Int,
)

class ScoringRepository(private val dataSource: DataSource) {

	/**
	 * Decay and trust weighting happen in SQL because they are pure aggregation over a large table;
	 * the scoring maths stays in Kotlin where it can be tested. This is the only place the two meet.
	 */
	fun aggregate(windowDays: Int = WINDOW_DAYS, halfLifeDays: Double = 7.0): List<SourceAggregate> =
		dataSource.connection.use { connection ->
			connection.prepareStatement(AGGREGATE).use { statement ->
				// Order matters and matches AGGREGATE: half-life first (inside power), window second.
				statement.setDouble(1, halfLifeDays)
				statement.setInt(2, windowDays)
				statement.executeQuery().use { rows ->
					buildList {
						while (rows.next()) {
							add(
								SourceAggregate(
									source = rows.getString("source"),
									region = rows.getString("region"),
									okWeighted = rows.getDouble("ok_w"),
									failWeighted = rows.getDouble("fail_w"),
									emptyWeighted = rows.getDouble("empty_w"),
									cfBlockedWeighted = rows.getDouble("cf_w"),
									latencyP50Ms = rows.getDouble("p50_w"),
									reporterWeight = rows.getDouble("reporter_w"),
									sampleSize = rows.getInt("sample_size"),
								),
							)
						}
					}
				}
			}
		}

	/**
	 * Replaces every region represented by [scores] in one transaction. Rows for sources no longer
	 * present in the aggregation are removed instead of accumulating forever.
	 */
	fun replace(scores: List<SourceScore>, now: OffsetDateTime): Int = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			val applied = if (scores.isEmpty()) 0 else connection.prepareStatement(UPSERT).use { statement ->
				scores.forEach { score ->
					statement.setString(1, score.source)
					statement.setString(2, score.region)
					statement.setFloat(3, score.stability.toFloat())
					statement.setFloat(4, score.popularity.toFloat())
					statement.setFloat(5, score.composite.toFloat())
					statement.setInt(6, score.sampleSize)
					statement.setObject(7, now, Types.TIMESTAMP_WITH_TIMEZONE)
					statement.addBatch()
				}
				statement.executeBatch().sum()
			}

			scores.groupBy { it.region }.forEach { (region, current) ->
				connection.prepareStatement(
					"DELETE FROM source_score WHERE region = ? AND source <> ALL (?)",
				).use { statement ->
					statement.setString(1, region)
					statement.setArray(2, connection.createArrayOf("text", current.map { it.source }.toTypedArray()))
					statement.executeUpdate()
				}
			}
			connection.prepareStatement("DELETE FROM source_score WHERE updated_at < ?").use { statement ->
				statement.setObject(1, now.minusDays(MAX_STALE_SCORE_DAYS), Types.TIMESTAMP_WITH_TIMEZONE)
				statement.executeUpdate()
			}
			connection.commit()
			applied
		} catch (error: Exception) {
			connection.rollback()
			throw error
		} finally {
			connection.autoCommit = true
		}
	}

	fun pruneStale(now: OffsetDateTime): Int = dataSource.connection.use { connection ->
		connection.prepareStatement("DELETE FROM source_score WHERE updated_at < ?").use { statement ->
			statement.setObject(1, now.minusDays(MAX_STALE_SCORE_DAYS), Types.TIMESTAMP_WITH_TIMEZONE)
			statement.executeUpdate()
		}
	}

	fun scoresFor(region: String): List<SourceScore> =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT source, region, stability, popularity, composite, sample_size
				FROM source_score WHERE region = ? ORDER BY composite DESC, source
				LIMIT ?
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, region)
				statement.setInt(2, MAX_SCORES_PER_REGION)
				statement.executeQuery().use { rows ->
					buildList {
						while (rows.next()) {
							add(
								SourceScore(
									source = rows.getString(1),
									region = rows.getString(2),
									stability = rows.getDouble(3),
									popularity = rows.getDouble(4),
									composite = rows.getDouble(5),
									sampleSize = rows.getInt(6),
								),
							)
						}
					}
				}
			}
		}

	/** Drives the ETag: the client re-downloads only when the scores were actually recomputed. */
	fun lastUpdated(region: String): OffsetDateTime? =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT max(updated_at) FROM source_score WHERE region = ?",
			).use { statement ->
				statement.setString(1, region)
				statement.executeQuery().use { rows ->
					if (rows.next()) rows.getObject(1, OffsetDateTime::class.java) else null
				}
			}
		}

	private companion object {
		const val WINDOW_DAYS = 30
		const val MAX_STALE_SCORE_DAYS = 30L
		const val MAX_SCORES_PER_REGION = 1_200

		/**
			 * Only established identities contribute to public scores. Device identifiers are self-declared,
			 * so three-day accounts remain cheap to manufacture in bulk even when each reporter-day is capped.
		 */
		val AGGREGATE = """
			WITH decayed AS (
				SELECT source, region, day, reporter_day, tier,
				       ok, fail, empty, cf_blocked, latency_p50_ms,
				       power(0.5, (CURRENT_DATE - day)::numeric / ?::numeric) AS w
				FROM source_probe_raw
				WHERE day >= CURRENT_DATE - ?::int AND tier >= 2
			),
			reporter_samples AS (
				SELECT source, region, day, reporter_day, MAX(tier) AS tier, MAX(w) AS w,
				       SUM(ok)::numeric AS ok,
				       SUM(fail)::numeric AS fail,
				       SUM(empty)::numeric AS empty,
				       SUM(cf_blocked)::numeric AS cf_blocked,
				       COALESCE(
				         SUM(latency_p50_ms::numeric * (ok + fail)) / NULLIF(SUM(ok + fail), 0),
				         0
				       ) AS latency
				FROM decayed
				GROUP BY source, region, day, reporter_day
			),
			counts AS (
				SELECT source, region,
				       SUM((ok / NULLIF(ok + fail, 0)) * w * (0.5 + tier * 0.25) * 20) AS ok_w,
				       SUM((fail / NULLIF(ok + fail, 0)) * w * (0.5 + tier * 0.25) * 20) AS fail_w,
				       SUM((empty / NULLIF(ok + fail, 0)) * w * (0.5 + tier * 0.25) * 20) AS empty_w,
				       SUM((cf_blocked / NULLIF(ok + fail, 0)) * w * (0.5 + tier * 0.25) * 20) AS cf_w,
				       SUM(latency * w * (0.5 + tier * 0.25)) AS lat_num,
				       SUM(w * (0.5 + tier * 0.25)) AS lat_den
				FROM reporter_samples GROUP BY source, region
			),
			reporters AS (
				SELECT source, region,
				       SUM(w * (0.5 + tier * 0.25)) AS reporter_w,
				       COUNT(*)                     AS sample_size
				FROM reporter_samples GROUP BY source, region
			)
			SELECT c.source, c.region,
			       c.ok_w::float8    AS ok_w,
			       c.fail_w::float8  AS fail_w,
			       c.empty_w::float8 AS empty_w,
			       c.cf_w::float8    AS cf_w,
			       COALESCE(c.lat_num / NULLIF(c.lat_den, 0), 0)::float8 AS p50_w,
			       r.reporter_w::float8 AS reporter_w,
			       r.sample_size
			FROM counts c
			JOIN reporters r ON r.source = c.source AND r.region = c.region
		""".trimIndent()

		val UPSERT = """
			INSERT INTO source_score (source, region, stability, popularity, composite, sample_size, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (source, region) DO UPDATE SET
				stability   = EXCLUDED.stability,
				popularity  = EXCLUDED.popularity,
				composite   = EXCLUDED.composite,
				sample_size = EXCLUDED.sample_size,
				updated_at  = EXCLUDED.updated_at
		""".trimIndent()
	}
}
