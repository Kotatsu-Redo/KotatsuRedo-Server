package io.kotatsuredo.server

import io.kotatsuredo.server.scoring.ScoreReplay
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The instrument §4 promised instead of an A/B test: replay the rollup, rank by the score as of one
 * day, and check the ranking against what the sources actually did on the next.
 */
class ScoreReplayTest {

	private val replay by lazy { ScoreReplay(PostgresTestBase.database.source) }
	private val start = LocalDate.of(2026, 8, 1)

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { it.execute("TRUNCATE source_probe_daily") }
		}
	}

	private fun day(day: LocalDate, source: String, reporters: Int, ok: Long, fail: Long, latency: Int = 400) {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO source_probe_daily
					(source, day, region, op, reporters, ok, fail, empty, cf_blocked, latency_mean_ms)
				VALUES (?, ?, 'EU', 0, ?, ?, ?, 0, 0, ?)
				""".trimIndent(),
			).use {
				it.setString(1, source)
				it.setObject(2, day)
				it.setInt(3, reporters)
				it.setLong(4, ok)
				it.setLong(5, fail)
				it.setInt(6, latency)
				it.executeUpdate()
			}
		}
	}

	@Test
	fun `a ranking that matches tomorrow scores near one`() {
		repeat(10) { index ->
			val at = start.plusDays(index.toLong())
			day(at, "STEADY", reporters = 20, ok = 190, fail = 10)
			day(at, "BROKEN", reporters = 20, ok = 5, fail = 195)
		}

		val result = replay.run(from = start, to = start.plusDays(9), region = "EU")

		assertTrue(result.days >= 8, "should have evaluated most days: ${result.days}")
		assertTrue(result.ndcg > 0.95, "the order was right every day: ${result.ndcg}")
		assertTrue(result.brier < 0.2, "and roughly calibrated: ${result.brier}")
	}

	/**
	 * Why the Wilson lower bound is in the formula, measured: a source with one lucky attempt
	 * yesterday tops a ranking by raw rate and collapses the next day.
	 */
	@Test
	fun `the score beats ranking by yesterday's raw success rate`() {
		repeat(10) { index ->
			val at = start.plusDays(index.toLong())
			day(at, "STEADY", reporters = 20, ok = 190, fail = 10)
			// One attempt, perfect, on alternate days; genuinely bad on the others.
			if (index % 2 == 0) day(at, "FLUKE", reporters = 1, ok = 1, fail = 0)
			else day(at, "FLUKE", reporters = 1, ok = 0, fail = 20)
		}

		val result = replay.run(from = start, to = start.plusDays(9), region = "EU")

		assertTrue(
			result.ndcg > result.ndcgNaive,
			"scored ${result.ndcg} should beat naive ${result.ndcgNaive}",
		)
	}

	@Test
	fun `an empty window is reported as nothing rather than as a perfect score`() {
		val result = replay.run(from = start, to = start.plusDays(3), region = "EU")

		assertEquals(0, result.days)
		assertEquals(0.0, result.ndcg)
	}

	/** Regions are scored apart, so a replay must not borrow another region's history. */
	@Test
	fun `only the region under test is replayed`() {
		repeat(4) { index ->
			day(start.plusDays(index.toLong()), "STEADY", reporters = 5, ok = 50, fail = 1)
		}

		assertEquals(0, replay.run(from = start, to = start.plusDays(3), region = "NA").days)
		assertTrue(replay.run(from = start, to = start.plusDays(3), region = "EU").days > 0)
	}
}
