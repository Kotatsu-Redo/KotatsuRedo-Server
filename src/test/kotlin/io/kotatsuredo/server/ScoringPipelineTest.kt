package io.kotatsuredo.server

import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.scoring.ScoringRepository
import io.kotatsuredo.server.scoring.ScoringService
import io.kotatsuredo.server.telemetry.Probe
import io.kotatsuredo.server.telemetry.ProbeOp
import io.kotatsuredo.server.telemetry.Region
import io.kotatsuredo.server.telemetry.TelemetryRepository
import io.kotatsuredo.server.telemetry.TelemetryService
import java.sql.Types
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Probes in, scores out, against a real database. */
class ScoringPipelineTest {

	private val telemetryRepository by lazy { TelemetryRepository(PostgresTestBase.database.source) }
	private val telemetry by lazy { TelemetryService(telemetryRepository) }
	private val scoringRepository by lazy { ScoringRepository(PostgresTestBase.database.source) }
	private val scoring by lazy { ScoringService(scoringRepository) }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { it.execute("TRUNCATE source_probe_raw, source_score") }
		}
	}

	private fun report(secret: String, source: String, ok: Int, fail: Int, empty: Int = 0, p50: Int = 700) {
		telemetry.record(
			secret,
			TrustTier.ESTABLISHED,
			Region.EU,
			listOf(Probe(source, ProbeOp.SEARCH, ok, fail, empty, 0, p50, p50 * 2)),
		)
	}

	@Test
	fun `a healthy source outranks a failing one`() {
		report("reporter-1", "GOOD", ok = 400, fail = 3)
		report("reporter-1", "BAD", ok = 10, fail = 300, p50 = 8000)

		scoring.recompute()
		val scores = scoring.scores(Region.EU.name).scores.associateBy { it.source }

		assertTrue(
			scores.getValue("GOOD").composite > scores.getValue("BAD").composite,
			"GOOD ${scores["GOOD"]} should outrank BAD ${scores["BAD"]}",
		)
	}

	@Test
	fun `a source returning only empty results is demoted below a working one`() {
		report("reporter-1", "WORKS", ok = 200, fail = 0)
		report("reporter-1", "EMPTY", ok = 200, fail = 0, empty = 200)

		scoring.recompute()
		val scores = scoring.scores(Region.EU.name).scores.associateBy { it.source }

		assertTrue(
			scores.getValue("EMPTY").stability < scores.getValue("WORKS").stability,
			"a parser returning nothing must not look healthy",
		)
	}

	@Test
	fun `popularity counts distinct reporters rather than events`() {
		// One enthusiastic user on HEAVY, three separate users on SPREAD.
		report("solo", "HEAVY", ok = 5000, fail = 0)
		report("user-a", "SPREAD", ok = 10, fail = 0)
		report("user-b", "SPREAD", ok = 10, fail = 0)
		report("user-c", "SPREAD", ok = 10, fail = 0)

		scoring.recompute()
		val scores = scoring.scores(Region.EU.name).scores.associateBy { it.source }

		assertTrue(
			scores.getValue("SPREAD").popularity > scores.getValue("HEAVY").popularity,
			"three users must beat one busy user: ${scores["SPREAD"]} vs ${scores["HEAVY"]}",
		)
	}

	/**
	 * The starvation loop in §4: an unproven source must not score near zero, or it is never queried,
	 * so it never earns data, so it is never queried. The client uses this median as its prior.
	 */
	@Test
	fun `the snapshot exposes a median so new sources are not starved`() {
		report("reporter-1", "ALPHA", ok = 300, fail = 2)
		report("reporter-1", "BETA", ok = 250, fail = 5)
		report("reporter-1", "GAMMA", ok = 100, fail = 40)

		scoring.recompute()
		val snapshot = scoring.scores(Region.EU.name)

		assertTrue(snapshot.medianComposite > 0.0, "median must be usable as an optimistic prior")
		assertTrue(snapshot.medianStability > 0.0)
		assertEquals(3, snapshot.scores.size)
	}

	@Test
	fun `sample size is reported so the client can tell proven from unproven`() {
		report("reporter-1", "SOLO", ok = 10, fail = 0)

		scoring.recompute()
		val score = scoring.scores(Region.EU.name).scores.single()

		assertEquals(1, score.sampleSize, "one reporter on one day is one reporter-day")
	}

	@Test
	fun `fresh and normal account telemetry is retained but excluded from public aggregation`() {
		telemetry.record(
			"fresh-attacker", TrustTier.NEW, Region.EU,
			listOf(Probe("SOURCE", ProbeOp.SEARCH, 0, 100_000, 0, 0, 100_000, 100_000)),
		)
		telemetry.record(
			"normal-attacker", TrustTier.NORMAL, Region.EU,
			listOf(Probe("SOURCE", ProbeOp.SEARCH, 0, 100_000, 0, 0, 100_000, 100_000)),
		)
		report("settled-reporter", "SOURCE", ok = 100, fail = 0)

		assertEquals(3L, telemetryRepository.countRows(), "raw privacy-limited snapshots are still accepted")
		val aggregate = scoringRepository.aggregate().single()
		assertEquals(1, aggregate.sampleSize)
		assertEquals(0.0, aggregate.failWeighted)
		assertTrue(aggregate.okWeighted > 0.0)
	}

	@Test
	fun `regions are scored independently`() {
		telemetry.record(
			"reporter-1", TrustTier.ESTABLISHED, Region.EU,
			listOf(Probe("GEO", ProbeOp.SEARCH, 200, 0, 0, 0, 600, 1200)),
		)
		telemetry.record(
			"reporter-1", TrustTier.ESTABLISHED, Region.APAC,
			listOf(Probe("GEO", ProbeOp.SEARCH, 2, 200, 0, 190, 9000, 12000)),
		)

		scoring.recompute()
		val eu = scoring.scores(Region.EU.name).scores.single()
		val apac = scoring.scores(Region.APAC.name).scores.single()

		assertTrue(
			eu.stability > apac.stability,
			"a source blocked in one region must not be demoted in another: EU=$eu APAC=$apac",
		)
	}

	/** Old data must fade, or a source that broke last month would still be dragging its score down. */
	@Test
	fun `recent data outweighs old data`() {
		insertProbeOn(LocalDate.now().minusDays(28), source = "FADED", ok = 0, fail = 500)
		insertProbeOn(LocalDate.now(), source = "FADED", ok = 500, fail = 0)

		scoring.recompute()
		val recovered = scoring.scores(Region.EU.name).scores.single()

		assertTrue(
			recovered.stability > 0.5,
			"a source that recovered should climb back, got ${recovered.stability}",
		)
	}

	@Test
	fun `recompute with no data is a no-op rather than a failure`() {
		assertEquals(0, scoring.recompute())
		assertNotNull(scoring.scores(Region.EU.name))
	}

	@Test
	fun `recompute removes scores that are no longer in an active region`() {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"INSERT INTO source_score " +
						"(source, region, stability, popularity, composite, sample_size) " +
						"VALUES ('STALE', 'EU', 1, 1, 1, 1)",
				)
			}
		}
		report("reporter", "ACTIVE", ok = 10, fail = 0)

		scoring.recompute()

		assertEquals(listOf("ACTIVE"), scoring.scores(Region.EU.name).scores.map { it.source })
	}

	@Test
	fun `score snapshots stay within the documented twelve hundred source budget`() {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO source_probe_raw
					(source, day, region, op, reporter_day, tier, ok, fail, empty, cf_blocked,
					 latency_p50_ms, latency_p90_ms)
				SELECT 'SOURCE_' || n, CURRENT_DATE, 'EU', 0, 'reporter_' || n, 2,
				       10, 0, 0, 0, 100, 200
				FROM generate_series(1, 1201) AS n
				""".trimIndent(),
			).use { it.executeUpdate() }
		}

		scoring.recompute()

		assertEquals(1_200, scoring.scores(Region.EU.name).scores.size)
	}

	private fun insertProbeOn(day: LocalDate, source: String, ok: Int, fail: Int) {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO source_probe_raw
					(source, day, region, op, reporter_day, tier, ok, fail, empty, cf_blocked,
					 latency_p50_ms, latency_p90_ms)
				VALUES (?, ?, 'EU', 0, ?, 2, ?, ?, 0, 0, 700, 1400)
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, source)
				statement.setObject(2, day, Types.DATE)
				statement.setString(3, "reporter-$day")
				statement.setInt(4, ok)
				statement.setInt(5, fail)
				statement.executeUpdate()
			}
		}
	}
}
