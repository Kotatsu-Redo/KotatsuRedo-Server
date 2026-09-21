package io.kotatsuredo.server

import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.ratings.RatingRepository
import io.kotatsuredo.server.ratings.RatingScale
import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.works.WorkRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingServiceTest {

	private val repository by lazy { RatingRepository(PostgresTestBase.database.source) }
	private val service by lazy { RatingService(repository) }
	private val works by lazy { WorkRepository(PostgresTestBase.database.source) }
	private val identities by lazy {
		IdentityService(IdentityRepository(PostgresTestBase.database.exposed, PostgresTestBase.database.source), DevicePepper.of("test"))
	}

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE rating, work_rating_agg, rating_brigade_flag, work, work_title, " +
						"work_alias, work_cover_hash, work_external_id, work_relation, work_merge_log, " +
						"app_user, user_active_day, app_device, device_ban, ban_evasion_flag CASCADE",
				)
			}
		}
	}

	private fun work(title: String = "Some Manga"): Long =
		works.createWork(title, 2018, "manga", nsfw = false)

	private fun user(seed: String): String {
		val outcome = identities.hello(seed, DeviceIdentifiers("dev-$seed", null))
		return (outcome as HelloOutcome.Ok).identity.id
	}

	// -- basics ----------------------------------------------------------------------------------

	@Test
	fun `a rating is stored and reflected in the aggregate`() {
		val workId = work()
		val aggregate = service.rate(workId, user("a"), value = 8)

		assertEquals(1, aggregate.count)
		assertEquals(8.0, aggregate.mean)
		assertEquals(8, service.myRating(workId, user("a")))
	}

	@Test
	fun `rating again replaces rather than adds`() {
		val workId = work()
		val userId = user("a")
		service.rate(workId, userId, 4)
		val aggregate = service.rate(workId, userId, 10)

		assertEquals(1, aggregate.count, "a user has one rating, not a history")
		assertEquals(10.0, aggregate.mean)
	}

	@Test
	fun `clearing removes the rating`() {
		val workId = work()
		val userId = user("a")
		service.rate(workId, userId, 6)
		val aggregate = service.clear(workId, userId)

		assertEquals(0, aggregate.count)
		assertNull(service.myRating(workId, userId))
	}

	@Test
	fun `an unrated work reports zeros rather than failing`() {
		val aggregate = service.aggregate(work())
		assertEquals(0, aggregate.count)
		assertEquals(0.0, aggregate.mean)
		assertEquals(List(5) { 0 }, aggregate.histogram)
	}

	/** Half-star storage: 2 is one star, 10 is five, and the histogram buckets by whole star. */
	@Test
	fun `the histogram buckets half stars into whole stars`() {
		val workId = work()
		service.rate(workId, user("a"), 10) // 5 stars
		service.rate(workId, user("b"), 9) // 4.5 -> bucket 5
		service.rate(workId, user("c"), 2) // 1 star
		val aggregate = service.aggregate(workId)

		assertEquals(3, aggregate.count)
		assertEquals(1, aggregate.histogram[0], "one 1-star: ${aggregate.histogram}")
		assertEquals(2, aggregate.histogram[4], "two 5-star: ${aggregate.histogram}")
		assertEquals(5.0, RatingScale.stars(10))
		assertEquals(1.0, RatingScale.stars(2))
	}

	// -- the Bayesian average --------------------------------------------------------------------

	/**
	 * The reason ranking never uses the raw mean: without a prior, one enthusiastic rating beats a
	 * work with hundreds.
	 */
	@Test
	fun `a single five star does not outrank a heavily rated work`() {
		// A realistic spread first, so the global mean sits mid-range. With every rating in the
		// database clustered at one value the prior has nothing to pull toward and the test would be
		// measuring nothing.
		val filler = work("Filler")
		repeat(40) { index -> service.rate(filler, user("f$index"), 4 + (index % 5)) }

		val popular = work("Popular")
		repeat(60) { index -> service.rate(popular, user("p$index"), 9) }
		val obscure = work("Obscure")
		service.rate(obscure, user("solo"), 10)

		val popularAgg = service.aggregate(popular)
		val obscureAgg = service.aggregate(obscure)

		assertTrue(obscureAgg.mean > popularAgg.mean, "raw means: ${obscureAgg.mean} vs ${popularAgg.mean}")
		assertTrue(
			popularAgg.bayesian > obscureAgg.bayesian,
			"bayesian must invert it: ${popularAgg.bayesian} vs ${obscureAgg.bayesian}",
		)
	}

	@Test
	fun `the bayesian value converges on the mean as ratings accumulate`() {
		val globalMean = 6.0
		val few = RatingService.bayesianAverage(1, 10.0, globalMean)
		val many = RatingService.bayesianAverage(500, 10.0, globalMean)

		assertTrue(few < many)
		assertTrue(many > 9.8, "with 500 ratings the prior should barely matter, got $many")
	}

	// -- brigade detection -------------------------------------------------------------------------

	/**
	 * A work collecting its first ratings is not a brigade, even though on a young instance every
	 * rater is tier 0 and the enthusiasm is genuine. This is the case the first implementation got
	 * wrong: with no history the baseline is zero and every burst looks like a spike.
	 */
	@Test
	fun `a new work collecting its first ratings is not flagged`() {
		val workId = work()
		repeat(15) { index -> service.rate(workId, user("new$index"), 10) }

		assertEquals(0, repository.unreviewedFlags(), "a work's first ratings are not an attack")
	}

	@Test
	fun `a small number of ratings never trips the detector`() {
		val workId = work()
		repeat(3) { index -> service.rate(workId, user("x$index"), 1) }

		assertFalse(service.detectBrigade(workId))
	}

	@Test
	fun `ordinary mid-range ratings are never a brigade`() {
		val workId = work()
		repeat(20) { index -> service.rate(workId, user("m$index"), 5 + (index % 3)) }

		assertEquals(0, repository.unreviewedFlags())
	}

	/** Moves every rating the work has so far out of the detection window, spread over [days]. */
	private fun age(workId: Long, days: Double) {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"""
				UPDATE rating SET updated_at = now() - interval '1 day'
					- make_interval(secs => ? * 86400 * random())
				WHERE work_id = ?
				""".trimIndent(),
			).use { statement ->
				statement.setDouble(1, days)
				statement.setLong(2, workId)
				statement.executeUpdate()
			}
		}
	}

	/** A liked work with three weeks of history: thirty 8s, roughly 1.4 ratings a day. */
	private fun establishedWork(): Long {
		val workId = work()
		repeat(30) { index -> service.rate(workId, user("old$index"), 8) }
		age(workId, days = 21.0)
		return workId
	}

	@Test
	fun `a burst of new accounts rating against the work's lean is flagged`() {
		val workId = establishedWork()
		repeat(12) { index -> service.rate(workId, user("bomb$index"), 1) }

		assertEquals(1, repository.unreviewedFlags())
	}

	/**
	 * The production false positive: fans of a well-liked work rating it 5 stars are extreme, new
	 * and bursty, and still not an attack.
	 */
	@Test
	fun `fans piling five stars onto a liked work are not flagged`() {
		val workId = establishedWork()
		repeat(12) { index -> service.rate(workId, user("fan$index"), 10) }

		assertEquals(0, repository.unreviewedFlags())
	}

	/** Thirty ratings from yesterday are thirty a day, not one a day spread over a month. */
	@Test
	fun `a young work's history is not stretched over the full baseline period`() {
		val workId = work()
		repeat(32) { index -> service.rate(workId, user("day1-$index"), 8) }
		age(workId, days = 0.5)
		repeat(13) { index -> service.rate(workId, user("day2-$index"), 1) }

		assertEquals(0, repository.unreviewedFlags())
	}

	// -- merge redirect ----------------------------------------------------------------------------

	/** Clients cache work ids forever, so a merged work has to be able to say where it went. */
	@Test
	fun `a merged work reports where it moved to`() {
		val from = work("Duplicate")
		val into = work("Canonical")
		works.mergeWorks(from = from, into = into, reason = "test")

		assertEquals(into, works.mergedInto(from))
		assertNull(works.metadataOf(from), "the merged-away work should be gone")
	}
}
