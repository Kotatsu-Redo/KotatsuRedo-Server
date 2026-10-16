package io.kotatsuredo.server

import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.telemetry.Probe
import io.kotatsuredo.server.telemetry.ProbeOp
import io.kotatsuredo.server.telemetry.Region
import io.kotatsuredo.server.telemetry.TelemetryRepository
import io.kotatsuredo.server.telemetry.TelemetryRetention
import io.kotatsuredo.server.telemetry.TelemetryService
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rollup is what lets raw rows be deleted on time and still leave enough history to tune scoring
 * with. Its defining property is what it does *not* hold: there is no reporter column, so a day that
 * survives the purge says how a source behaved and nothing whatsoever about who saw it.
 */
class TelemetryRollupTest {

	private val repository by lazy { TelemetryRepository(PostgresTestBase.database.source) }
	private val telemetry by lazy { TelemetryService(repository) }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { it.execute("TRUNCATE source_probe_raw, source_probe_daily") }
		}
	}

	private fun retentionAt(today: LocalDate, retentionDays: Long = 7) = TelemetryRetention(
		repository,
		clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC),
		retentionDays = retentionDays,
	)

	private fun insertRaw(day: LocalDate, source: String, reporter: String, ok: Int, fail: Int, p50: Int = 500) {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO source_probe_raw
					(source, day, region, op, reporter_day, tier, ok, fail, empty, cf_blocked,
					 latency_p50_ms, latency_p90_ms)
				VALUES (?, ?, 'EU', 0, ?, 2, ?, ?, 0, 0, ?, ?)
				""".trimIndent(),
			).use {
				it.setString(1, source)
				it.setObject(2, day)
				it.setString(3, reporter)
				it.setInt(4, ok)
				it.setInt(5, fail)
				it.setInt(6, p50)
				it.setInt(7, p50 * 2)
				it.executeUpdate()
			}
		}
	}

	private fun rolled(day: LocalDate, source: String): Triple<Int, Long, Long>? =
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"SELECT reporters, ok, fail FROM source_probe_daily WHERE day = ? AND source = ?",
			).use {
				it.setObject(1, day)
				it.setString(2, source)
				it.executeQuery().use { rows ->
					if (rows.next()) Triple(rows.getInt(1), rows.getLong(2), rows.getLong(3)) else null
				}
			}
		}

	@Test
	fun `a day is folded into one row per source, region and operation`() {
		val day = LocalDate.of(2026, 9, 1)
		insertRaw(day, "SOURCE", "reporter-1", ok = 10, fail = 1)
		insertRaw(day, "SOURCE", "reporter-2", ok = 20, fail = 3)

		repository.rollUpThrough(day)

		assertEquals(Triple(2, 30L, 4L), rolled(day, "SOURCE"))
	}

	@Test
	fun `rolling up twice does not double count`() {
		val day = LocalDate.of(2026, 9, 1)
		insertRaw(day, "SOURCE", "reporter-1", ok = 10, fail = 1)

		repository.rollUpThrough(day)
		repository.rollUpThrough(day)

		assertEquals(Triple(1, 10L, 1L), rolled(day, "SOURCE"))
	}

	@Test
	fun `late arrivals for a rolled-up day are picked up on the next pass`() {
		val day = LocalDate.of(2026, 9, 1)
		insertRaw(day, "SOURCE", "reporter-1", ok = 10, fail = 1)
		repository.rollUpThrough(day)

		insertRaw(day, "SOURCE", "reporter-2", ok = 5, fail = 0)
		repository.rollUpThrough(day)

		assertEquals(Triple(2, 15L, 1L), rolled(day, "SOURCE"))
	}

	/** The privacy property, asserted rather than assumed. */
	@Test
	fun `the rollup holds no reporter column at all`() {
		val columns = PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery(
					"SELECT column_name FROM information_schema.columns WHERE table_name = 'source_probe_daily'",
				).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
			}
		}

		assertTrue(columns.none { it.contains("reporter_day") }, "columns: $columns")
		assertTrue("reporters" in columns, "a count is fine; an identifier is not: $columns")
	}

	@Test
	fun `the scheduled job rolls up every complete day but leaves today alone`() {
		val today = LocalDate.of(2026, 9, 10)
		insertRaw(today.minusDays(1), "SOURCE", "reporter-1", ok = 4, fail = 0)
		insertRaw(today, "SOURCE", "reporter-1", ok = 9, fail = 0)

		retentionAt(today).rollUpNow()

		assertEquals(Triple(1, 4L, 0L), rolled(today.minusDays(1), "SOURCE"))
		assertEquals(null, rolled(today, "SOURCE"), "today is still filling up")
	}

	@Test
	fun `purging deletes raw rows once they are rolled up`() {
		val today = LocalDate.of(2026, 9, 10)
		val old = today.minusDays(9)
		insertRaw(old, "SOURCE", "reporter-1", ok = 4, fail = 1)
		val retention = retentionAt(today)
		retention.rollUpNow()

		assertEquals(1, retention.purgeNow())
		assertEquals(Triple(1, 4L, 1L), rolled(old, "SOURCE"), "the day survives as totals")
	}

	/**
	 * Retention is a promise to users; history for tuning a ranking is not. A rollup that has been
	 * failing costs the history, never the promise - not even by a day.
	 */
	@Test
	fun `a day the rollup never covered is still deleted at the retention cutoff`() {
		val today = LocalDate.of(2026, 9, 10)
		insertRaw(today.minusDays(8), "SOURCE", "reporter-1", ok = 4, fail = 1)

		assertEquals(1, retentionAt(today).purgeNow(), "the promise outranks the history")
		assertEquals(0L, repository.countRows())
		assertEquals(null, rolled(today.minusDays(8), "SOURCE"), "and that day's totals are gone")
	}

	@Test
	fun `rows inside the retention window are kept`() {
		val today = LocalDate.of(2026, 9, 10)
		insertRaw(today.minusDays(TelemetryRetention.RETENTION_DAYS), "SOURCE", "reporter-1", ok = 4, fail = 1)

		assertEquals(0, retentionAt(today).purgeNow())
		assertEquals(1L, repository.countRows())
	}
}
