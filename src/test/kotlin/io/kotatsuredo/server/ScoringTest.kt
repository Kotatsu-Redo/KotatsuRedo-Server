package io.kotatsuredo.server

import io.kotatsuredo.server.scoring.Scoring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure maths, no database. These are the properties the ranking depends on being true. */
class ScoringTest {

	/**
	 * The single most important property in §4: a source with one lucky success must not outrank a
	 * source with thousands. This is why the raw ratio is not used.
	 */
	@Test
	fun `a tiny perfect sample loses to a large near-perfect one`() {
		val lucky = Scoring.wilsonLowerBound(successes = 1.0, total = 1.0)
		val proven = Scoring.wilsonLowerBound(successes = 4000.0, total = 4012.0)

		assertTrue(lucky < proven, "1/1 ($lucky) must not outrank 4000/4012 ($proven)")
	}

	@Test
	fun `wilson bound rises with evidence at a fixed success rate`() {
		val small = Scoring.wilsonLowerBound(9.0, 10.0)
		val medium = Scoring.wilsonLowerBound(90.0, 100.0)
		val large = Scoring.wilsonLowerBound(900.0, 1000.0)

		assertTrue(small < medium && medium < large, "$small < $medium < $large")
	}

	@Test
	fun `wilson bound handles the degenerate cases`() {
		assertEquals(0.0, Scoring.wilsonLowerBound(0.0, 0.0))
		assertEquals(0.0, Scoring.wilsonLowerBound(0.0, 100.0))
		assertTrue(Scoring.wilsonLowerBound(100.0, 100.0) in 0.0..1.0)
	}

	// -- latency ---------------------------------------------------------------------------------

	@Test
	fun `latency below the floor is not penalised`() {
		assertEquals(1.0, Scoring.latencyScore(0.0))
		assertEquals(1.0, Scoring.latencyScore(Scoring.LATENCY_FLOOR_MS))
	}

	@Test
	fun `latency score decreases and bottoms out rather than going negative`() {
		assertTrue(Scoring.latencyScore(2000.0) < Scoring.latencyScore(1000.0))
		assertEquals(0.0, Scoring.latencyScore(60_000.0))
	}

	// -- stability -------------------------------------------------------------------------------

	@Test
	fun `a healthy source scores near the top and a dead one near the bottom`() {
		val healthy = Scoring.stability(1000.0, 5.0, 0.0, 0.0, 600.0)
		val dead = Scoring.stability(5.0, 1000.0, 0.0, 0.0, 9000.0)

		assertTrue(healthy > 0.85, "healthy source scored $healthy")
		assertTrue(dead < 0.15, "dead source scored $dead")
	}

	/**
	 * A parser returning 200 OK with zero results never registers a failure, so without the empty
	 * penalty a comprehensively broken source would look perfectly healthy.
	 */
	@Test
	fun `a source returning empty results is penalised despite never failing`() {
		val working = Scoring.stability(100.0, 0.0, 0.0, 0.0, 600.0)
		val broken = Scoring.stability(100.0, 0.0, 100.0, 0.0, 600.0)

		assertTrue(broken < working, "empty-returning source ($broken) must score below $working")
	}

	@Test
	fun `cloudflare blocks are penalised`() {
		val clear = Scoring.stability(100.0, 10.0, 0.0, 0.0, 600.0)
		val blocked = Scoring.stability(100.0, 10.0, 0.0, 80.0, 600.0)

		assertTrue(blocked < clear, "cf-blocked ($blocked) must score below clear ($clear)")
	}

	/** Scores are shown to users as a reliability indicator, so they have to mean what they look like. */
	@Test
	fun `stability stays within zero and one`() {
		listOf(
			Scoring.stability(0.0, 0.0, 0.0, 0.0, 0.0),
			Scoring.stability(1e6, 0.0, 0.0, 0.0, 0.0),
			Scoring.stability(1.0, 1e6, 1e6, 1e6, 1e6),
		).forEach { assertTrue(it in 0.0..1.0, "out of range: $it") }
	}

	// -- popularity and decay --------------------------------------------------------------------

	@Test
	fun `popularity is log scaled so the biggest source does not flatten the rest`() {
		val biggest = Scoring.popularity(10_000.0, 10_000.0)
		val middling = Scoring.popularity(100.0, 10_000.0)

		assertEquals(1.0, biggest)
		assertTrue(middling > 0.4, "log scaling should keep a 1%-of-max source visible, got $middling")
	}

	@Test
	fun `decay halves every seven days`() {
		assertEquals(1.0, Scoring.decayWeight(0.0))
		assertTrue(kotlin.math.abs(Scoring.decayWeight(7.0) - 0.5) < 1e-9)
		assertTrue(kotlin.math.abs(Scoring.decayWeight(14.0) - 0.25) < 1e-9)
	}

	@Test
	fun `composite sits between its inputs`() {
		val composite = Scoring.composite(stability = 0.9, popularity = 0.3)
		assertTrue(composite in 0.3..0.9, "composite $composite should lie between its inputs")
		assertTrue(
			composite > Scoring.composite(0.5, 0.3),
			"composite must increase with stability",
		)
	}
}
