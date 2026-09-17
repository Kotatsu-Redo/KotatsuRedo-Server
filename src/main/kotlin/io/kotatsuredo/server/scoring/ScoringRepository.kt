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
		 * A reporter-day is evidence worth at most this many attempts. One failed try is one failure, not
		 * as much as a device that made hundreds - and no single device can outvote many others either.
		 */
		const val MAX_EVIDENCE_PER_SAMPLE = 20

		/** Below this many sources in a day there is too little to tell a broken device from bad luck. */
		const val DEVICE_FAILURE_MIN_SOURCES = 3

		/** Share of a reporter's sources that mostly failed before the device, not the sources, is blamed. */
		const val DEVICE_FAILURE_SHARE = 0.8

		/**
		 * Every authenticated identity contributes. A reporter counts once per source and day; seniority
		 * changes its bounded weight instead of deciding whether its telemetry exists at all.
		 *
		 * Stability excludes reporter-days where the *device* looks broken: offline, captive portal, dead
		 * DNS or a bad VPN fail every source at once, and averaging that in would demote whatever those
		 * devices happened to use. A source that only such devices reported falls back to their data -
		 * a real outage everywhere must still show, and discarding it all would score the source as zero.
		 * Popularity and sample size still count every reporter: a broken device still used the source.
		 */
		val AGGREGATE = """
			WITH decayed AS (
				SELECT source, region, day, reporter_day, tier,
				       ok, fail, empty, cf_blocked, latency_p50_ms,
				       power(
				         0.5,
				         ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date - day)::numeric / ?::numeric
				       ) AS w
				FROM source_probe_raw
				WHERE day >= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date - ?::int
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
			device_health AS (
				SELECT region, day, reporter_day,
				       NOT (
				         COUNT(*) >= $DEVICE_FAILURE_MIN_SOURCES
				         AND COUNT(*) FILTER (WHERE fail > ok) >= $DEVICE_FAILURE_SHARE * COUNT(*)
				       ) AS healthy
				FROM reporter_samples
				WHERE ok + fail > 0
				GROUP BY region, day, reporter_day
			),
			weighted AS (
				SELECT s.source, s.region, s.ok, s.fail, s.empty, s.cf_blocked, s.latency,
				       s.w * (0.5 + s.tier * 0.25) AS trust_w,
				       LEAST(s.ok + s.fail, $MAX_EVIDENCE_PER_SAMPLE) AS evidence,
				       COALESCE(h.healthy, TRUE) AS healthy
				FROM reporter_samples s
				LEFT JOIN device_health h USING (region, day, reporter_day)
			),
			selected AS (
				SELECT *,
				       healthy OR NOT bool_or(healthy) OVER (PARTITION BY source, region) AS counted
				FROM weighted
			),
			aggregated AS (
				SELECT source, region,
				       SUM((ok / NULLIF(ok + fail, 0)) * trust_w * evidence) FILTER (WHERE counted) AS ok_w,
				       SUM((fail / NULLIF(ok + fail, 0)) * trust_w * evidence) FILTER (WHERE counted) AS fail_w,
				       SUM((empty / NULLIF(ok + fail, 0)) * trust_w * evidence) FILTER (WHERE counted) AS empty_w,
				       SUM((cf_blocked / NULLIF(ok + fail, 0)) * trust_w * evidence) FILTER (WHERE counted) AS cf_w,
				       SUM(latency * trust_w * evidence) FILTER (WHERE counted) AS lat_num,
				       SUM(trust_w * evidence) FILTER (WHERE counted) AS lat_den,
				       SUM(trust_w)                 AS weight,
				       COUNT(*)                     AS sample_size
				FROM selected GROUP BY source, region
			)
			SELECT source, region,
			       COALESCE(ok_w, 0)::float8    AS ok_w,
			       COALESCE(fail_w, 0)::float8  AS fail_w,
			       COALESCE(empty_w, 0)::float8 AS empty_w,
			       COALESCE(cf_w, 0)::float8    AS cf_w,
			       COALESCE(lat_num / NULLIF(lat_den, 0), 0)::float8 AS p50_w,
			       weight::float8 AS reporter_w,
			       sample_size
			FROM aggregated
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
