package io.kotatsuredo.server.telemetry

import java.sql.Types
import java.time.LocalDate
import javax.sql.DataSource

/**
 * Bulk ingest, written against plain JDBC rather than the ORM.
 *
 * This is the highest-volume write in the system (roughly one batch per device per day) and it is a
 * batched upsert with arithmetic in the conflict clause - exactly what a prepared statement with
 * `addBatch` is for, and awkward to express through Exposed's DSL.
 */
class TelemetryRepository(private val dataSource: DataSource) {

	/**
	 * Adds counts into the reporter's row for the day. Re-uploading the same window is additive
	 * rather than idempotent, which is the right trade here: the client only uploads once per day,
	 * and a lost upload matters more than a double-counted one for a single reporter among thousands.
	 */
	fun record(
		probes: List<Probe>,
		day: LocalDate,
		region: Region,
		reporterId: String,
		tier: Int,
	): Int {
		if (probes.isEmpty()) return 0

		return dataSource.connection.use { connection ->
			connection.autoCommit = false
			connection.prepareStatement(UPSERT).use { statement ->
				probes.forEach { probe ->
					statement.setString(1, probe.source)
					statement.setObject(2, day, Types.DATE)
					statement.setString(3, region.name)
					statement.setShort(4, probe.op.code)
					statement.setString(5, reporterId)
					statement.setShort(6, tier.toShort())
					statement.setInt(7, probe.ok)
					statement.setInt(8, probe.fail)
					statement.setInt(9, probe.empty)
					statement.setInt(10, probe.cfBlocked)
					statement.setInt(11, probe.latencyP50Ms)
					statement.setInt(12, probe.latencyP90Ms)
					statement.addBatch()
				}
				val applied = statement.executeBatch().sum()
				connection.commit()
				applied
			}
		}
	}

	/**
	 * Retention is part of the privacy promise, not housekeeping: raw per-reporter rows exist only
	 * until they have been rolled up, plus a short grace window (PLAN.md §6).
	 */
	fun purgeOlderThan(cutoff: LocalDate): Int =
		dataSource.connection.use { connection ->
			connection.prepareStatement("DELETE FROM source_probe_raw WHERE day < ?").use { statement ->
				statement.setObject(1, cutoff, Types.DATE)
				statement.executeUpdate()
			}
		}

	fun countRows(): Long =
		dataSource.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("SELECT count(*) FROM source_probe_raw").use { rows ->
					rows.next()
					rows.getLong(1)
				}
			}
		}

	private companion object {
		/**
		 * Latency is kept as the max of what has been reported rather than averaged: these are
		 * already per-device percentiles, and averaging percentiles is meaningless. The rollup in M6
		 * aggregates properly across reporters.
		 */
		val UPSERT = """
			INSERT INTO source_probe_raw
				(source, day, region, op, reporter_day, tier,
				 ok, fail, empty, cf_blocked, latency_p50_ms, latency_p90_ms)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (source, day, region, op, reporter_day) DO UPDATE SET
				ok             = source_probe_raw.ok         + EXCLUDED.ok,
				fail           = source_probe_raw.fail       + EXCLUDED.fail,
				empty          = source_probe_raw.empty      + EXCLUDED.empty,
				cf_blocked     = source_probe_raw.cf_blocked + EXCLUDED.cf_blocked,
				latency_p50_ms = GREATEST(source_probe_raw.latency_p50_ms, EXCLUDED.latency_p50_ms),
				latency_p90_ms = GREATEST(source_probe_raw.latency_p90_ms, EXCLUDED.latency_p90_ms),
				tier           = EXCLUDED.tier,
				updated_at     = now()
		""".trimIndent()
	}
}
