package io.kotatsuredo.server

import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.identity.dailyReporterId
import io.kotatsuredo.server.telemetry.Probe
import io.kotatsuredo.server.telemetry.ProbeLimits
import io.kotatsuredo.server.telemetry.ProbeOp
import io.kotatsuredo.server.telemetry.Region
import io.kotatsuredo.server.telemetry.TelemetryRepository
import io.kotatsuredo.server.telemetry.TelemetryRetention
import io.kotatsuredo.server.telemetry.TelemetryService
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TelemetryServiceTest {

	private val repository by lazy { TelemetryRepository(PostgresTestBase.database.source) }
	private val service by lazy { TelemetryService(repository) }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { it.execute("TRUNCATE source_probe_raw") }
		}
	}

	private fun probe(
		source: String = "MANGADEX",
		op: ProbeOp = ProbeOp.SEARCH,
		ok: Int = 10,
		fail: Int = 2,
		empty: Int = 0,
		cf: Int = 0,
		p50: Int = 800,
		p90: Int = 2000,
	) = Probe(source, op, ok, fail, empty, cf, p50, p90)

	@Test
	fun `a batch is stored`() {
		val accepted = service.record("secret", TrustTier.NORMAL, Region.EU, listOf(probe()))

		assertEquals(1, accepted)
		assertEquals(1L, repository.countRows())
	}

	@Test
	fun `repeat uploads for the same day replace the daily snapshot`() {
		service.record("secret", TrustTier.NORMAL, Region.EU, listOf(probe(ok = 10, fail = 2)))
		service.record("secret", TrustTier.NORMAL, Region.EU, listOf(probe(ok = 5, fail = 1)))

		assertEquals(1L, repository.countRows(), "same reporter, day, source and op must be one row")
		assertEquals(5, columnValue("ok"))
		assertEquals(1, columnValue("fail"))
	}

	@Test
	fun `two reporters produce two rows`() {
		service.record("secret-a", TrustTier.NORMAL, Region.EU, listOf(probe()))
		service.record("secret-b", TrustTier.NORMAL, Region.EU, listOf(probe()))

		assertEquals(2L, repository.countRows())
	}

	/**
	 * The reporter pseudonym must rotate daily and must not be derivable from the user id, or the
	 * privacy claim in §6 is empty.
	 */
	@Test
	fun `the reporter pseudonym changes every day`() {
		val monday = dailyReporterId("secret", "2026-09-07")
		val tuesday = dailyReporterId("secret", "2026-09-08")

		assertNotEquals(monday, tuesday)
		assertEquals(monday, dailyReporterId("secret", "2026-09-07"), "must be stable within a day")
	}

	/** The stored pseudonym must not be the secret, nor anything trivially derived from it. */
	@Test
	fun `no secret or user id is stored in the telemetry table`() {
		val secret = "telemetry-secret-plaintext"
		service.record(secret, TrustTier.NORMAL, Region.EU, listOf(probe()))

		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("SELECT reporter_day, source FROM source_probe_raw").use { rows ->
					assertTrue(rows.next())
					val reporter = rows.getString(1)
					assertFalse(reporter.contains(secret))
					assertNotEquals(secret, reporter)
				}
			}
		}
	}

	// -- sanitising ------------------------------------------------------------------------------

	@Test
	fun `empty count cannot exceed successes`() {
		service.record("secret", TrustTier.NORMAL, Region.EU, listOf(probe(ok = 3, empty = 99)))

		assertEquals(3, columnValue("empty"), "empty is a subset of ok and must be clamped to it")
	}

	@Test
	fun `probes with no counts at all are dropped`() {
		val accepted = service.record(
			"secret",
			TrustTier.NORMAL,
			Region.EU,
			listOf(probe(ok = 0, fail = 0, empty = 0, cf = 0)),
		)

		assertEquals(0, accepted)
		assertEquals(0L, repository.countRows())
	}

	@Test
	fun `invalid source identifiers are dropped rather than truncated into database keys`() {
		val accepted = service.record(
			"secret",
			TrustTier.NORMAL,
			Region.EU,
			listOf(
				probe(source = "contains spaces"),
				probe(source = "x".repeat(ProbeLimits.MAX_SOURCE_NAME + 1)),
			),
		)

		assertEquals(0, accepted)
		assertEquals(0L, repository.countRows())
	}

	@Test
	fun `absurd latency is clamped rather than rejected`() {
		service.record("secret", TrustTier.NORMAL, Region.EU, listOf(probe(p50 = Int.MAX_VALUE)))

		assertEquals(1L, repository.countRows())
		assertTrue(columnValue("latency_p50_ms") <= 120_000)
	}

	@Test
	fun `an unknown region falls back to OTHER instead of failing`() {
		assertEquals(Region.OTHER, Region.parse("mars"))
		assertEquals(Region.OTHER, Region.parse(null))
		assertEquals(Region.EU, Region.parse("eu"))
	}

	// -- retention -------------------------------------------------------------------------------

	/**
	 * "Raw probe rows are deleted within 7 days" is a sentence in the privacy notice, so it gets a
	 * test rather than a cron job nobody checks.
	 */
	@Test
	fun `rows older than the retention window are purged`() {
		val today = LocalDate.of(2026, 9, 10)
		val fixed = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC)

		insertRawRow(day = today.minusDays(9))
		insertRawRow(day = today.minusDays(2), reporter = "recent")
		assertEquals(2L, repository.countRows())

		val purged = TelemetryRetention(repository, fixed).purgeNow()

		assertEquals(1, purged)
		assertEquals(1L, repository.countRows(), "only the row inside the window should survive")
	}

	private fun insertRawRow(day: LocalDate, reporter: String = "old") {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO source_probe_raw
					(source, day, region, op, reporter_day, tier, ok, fail, empty, cf_blocked,
					 latency_p50_ms, latency_p90_ms)
				VALUES ('SRC', ?, 'EU', 0, ?, 1, 1, 0, 0, 0, 100, 200)
				""".trimIndent(),
			).use { statement ->
				statement.setObject(1, day, Types.DATE)
				statement.setString(2, reporter)
				statement.executeUpdate()
			}
		}
	}

	private fun columnValue(column: String): Int =
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("SELECT $column FROM source_probe_raw LIMIT 1").use { rows ->
					rows.next()
					rows.getInt(1)
				}
			}
		}
}
