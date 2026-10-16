package io.kotatsuredo.server

import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.routes.SimilarWorks
import io.kotatsuredo.server.works.WorkRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What "works like this one" is made of beyond genres - ratings, readership, era, co-reading - and
 * the batching that keeps building it to three queries.
 *
 * Every signal has to do two things: move the order the way it says, and do nothing at all where
 * there is no data, so a server with no ratings and no co-reading answers exactly as before.
 */
class SimilarityTest {

	private val repository by lazy { WorkRepository(PostgresTestBase.database.source) }
	private val identities by lazy {
		IdentityService(
			IdentityRepository(PostgresTestBase.database.exposed, PostgresTestBase.database.source),
			DevicePepper.of("test"),
		)
	}

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		sql(
			"TRUNCATE work, work_title, work_genre, work_genre_observation, work_alias, " +
				"work_alias_observation, work_coread, rating, work_rating_agg, app_user, user_active_day, " +
				"app_device CASCADE",
		)
		sql("UPDATE rating_global_shard SET count = 0, value_sum = 0")
	}

	private fun sql(statement: String) {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { it.execute(statement) }
		}
	}

	private fun user(seed: String): String {
		val outcome = identities.hello(seed, DeviceIdentifiers("dev-$seed", null))
		return (outcome as HelloOutcome.Ok).identity.id
	}

	private fun users(prefix: String, count: Int) = List(count) { user("$prefix-$it") }

	private fun work(title: String, year: Int? = 2019, genres: List<String> = emptyList()): Long =
		repository.createWork(title, year, "manga", false).also {
			if (genres.isNotEmpty()) repository.applyCatalogueGenres(it, genres)
		}

	private fun seen(workId: Long, source: String, key: String, users: Collection<String>) {
		users.forEach { repository.observeAlias(source, key, it, workId, listOf("key")) }
	}

	private fun ban(userId: String) = sql("UPDATE app_user SET is_banned = TRUE WHERE id = '$userId'")

	private fun rated(workId: Long, bayesian: Double) {
		sql(
			"INSERT INTO work_rating_agg (work_id, count, value_sum, mean, bayesian) " +
				"VALUES ($workId, 200, ${(bayesian * 200).toLong()}, $bayesian, $bayesian)",
		)
	}

	private fun globalMean(mean: Double) {
		sql("UPDATE rating_global_shard SET count = 1000, value_sum = ${(mean * 1000).toLong()} WHERE shard = 0")
	}

	private fun coReadPairs(): List<Triple<Long, Long, Int>> =
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("SELECT work_a, work_b, readers FROM work_coread ORDER BY work_a, work_b").use { rows ->
					buildList { while (rows.next()) add(Triple(rows.getLong(1), rows.getLong(2), rows.getInt(3))) }
				}
			}
		}

	// -- batching ----------------------------------------------------------------------------------

	/** One query for every candidate's genres and one for their sources, answering exactly as before. */
	@Test
	fun `batched genres and sources match the per-work lookups`() {
		val (a, b, c, d) = users("batch", 4)
		val banned = user("batch-banned")
		ban(banned)
		val crowded = work("Crowded", genres = listOf("Action", "Villainess"))
		seen(crowded, "MANGADEX", "x1", listOf(a, b, c))
		seen(crowded, "COMICK", "x2", listOf(a, b))
		seen(crowded, "LONELY", "x3", listOf(a)) // one account: never reported
		seen(crowded, "SANCTIONED", "x4", listOf(a, banned)) // the ban leaves one
		listOf("S1", "S2", "S3", "S4", "S5", "S6").forEach { seen(crowded, it, "k-$it", listOf(c, d)) }
		val plain = work("Plain", genres = listOf("Romance"))
		seen(plain, "MANGADEX", "y1", listOf(b, d))
		val bare = work("Bare")

		val ids = listOf(crowded, plain, bare)
		val genres = repository.genresOfAll(ids)
		val sources = repository.sourcesForAll(ids, limit = 5)

		ids.forEach { id ->
			assertEquals(repository.genresOf(id), genres[id].orEmpty(), "genres of $id")
			assertEquals(repository.sourcesFor(id, limit = 5), sources[id].orEmpty(), "sources of $id")
		}
		assertEquals(5, sources[crowded]?.size, "the per-work limit applies to each work")
	}

	// -- signals -----------------------------------------------------------------------------------

	@Test
	fun `with no ratings readers or co-reading two equal matches score the same`() {
		val seed = work("Seed", genres = listOf("Villainess", "Isekai", "Action"))
		val first = work("First", genres = listOf("Villainess", "Isekai"))
		val second = work("Second", genres = listOf("Villainess", "Isekai"))

		val similar = repository.similarTo(seed).associateBy { it.workId }

		assertEquals(similar.getValue(first).score, similar.getValue(second).score, 1e-12)
	}

	@Test
	fun `a well rated work outranks an equal match that is poorly rated`() {
		globalMean(6.0)
		val seed = work("Seed", genres = listOf("Villainess", "Isekai"))
		// Created first, so the poorly rated one has the lower id and would win the tie without the prior.
		val poor = work("Poor", genres = listOf("Villainess", "Isekai"))
		val good = work("Good", genres = listOf("Villainess", "Isekai"))
		rated(good, 8.5)
		rated(poor, 3.5)

		val similar = repository.similarTo(seed)

		assertEquals(listOf("Good", "Poor"), similar.map { it.title }.filter { it == "Good" || it == "Poor" })
		val scores = similar.associate { it.title to it.score }
		assertTrue(scores.getValue("Good") / scores.getValue("Poor") < 1.4, "the prior is mild: $scores")
	}

	@Test
	fun `readership breaks a tie but cannot beat a closer match`() {
		val seed = work("Seed", genres = listOf("Villainess", "Isekai", "Revenge", "Action"))
		work("Niche", genres = listOf("Villainess", "Isekai", "Revenge"))
		// Created before its popular twin, so the tie would go to it on id alone.
		work("Quiet twin", genres = listOf("Villainess", "Isekai"))
		val popularTwin = work("Popular twin", genres = listOf("Villainess", "Isekai"))
		seen(popularTwin, "MANGADEX", "p", users("reader", 30))

		val order = repository.similarTo(seed).map { it.title }

		assertEquals("Niche", order.first(), "three shared rare genres beat popularity: $order")
		assertTrue(order.indexOf("Popular twin") < order.indexOf("Quiet twin"), "readers break the tie: $order")
	}

	@Test
	fun `a work decades apart costs a little and never more than a fifth`() {
		val seed = work("Seed", year = 2019, genres = listOf("Villainess", "Isekai"))
		work("Recent", year = 2018, genres = listOf("Villainess", "Isekai"))
		work("Old", year = 1975, genres = listOf("Villainess", "Isekai"))
		work("Undated", year = null, genres = listOf("Villainess", "Isekai"))

		val scores = repository.similarTo(seed).associate { it.title to it.score }

		assertTrue(scores.getValue("Recent") > scores.getValue("Old"), "$scores")
		assertTrue(scores.getValue("Old") >= 0.8 * scores.getValue("Undated"), "the era floor holds: $scores")
		assertTrue(scores.getValue("Undated") >= scores.getValue("Recent"), "no year, no penalty: $scores")
	}

	// -- co-reading --------------------------------------------------------------------------------

	@Test
	fun `a pair is stored only once ten accounts opened both, and no account with it`() {
		val first = work("First")
		val second = work("Second")
		val third = work("Third")
		val ten = users("co", 10)
		seen(first, "MANGADEX", "f", ten)
		seen(second, "COMICK", "s", ten)
		seen(third, "MANGADEX", "t", ten.take(9))

		val result = repository.refreshCoReading()

		assertEquals(listOf(Triple(first, second, 10)), coReadPairs(), "nine shared readers are not enough")
		assertEquals(1L, result.pairs)
	}

	@Test
	fun `sanctioned accounts do not count towards a pair`() {
		val first = work("First")
		val second = work("Second")
		val ten = users("ban", 10)
		seen(first, "MANGADEX", "f", ten)
		seen(second, "MANGADEX", "s", ten)
		ban(ten.last())

		repository.refreshCoReading()

		assertTrue(coReadPairs().isEmpty(), "the ban leaves nine")
	}

	/** One account's whole history never defines everyone's neighbours: only its latest works count. */
	@Test
	fun `each account contributes only its most recently opened works`() {
		val old = work("Old")
		val newer = work("Newer")
		val newest = work("Newest")
		val readers = users("cap", 10)
		seen(old, "MANGADEX", "o", readers)
		seen(newer, "MANGADEX", "n", readers)
		seen(newest, "MANGADEX", "w", readers)
		sql("UPDATE work_alias_observation SET updated_at = now() - interval '30 days' WHERE work_id = $old")
		sql("UPDATE work_alias_observation SET updated_at = now() - interval '1 day' WHERE work_id = $newer")

		repository.refreshCoReading(perAccountCap = 2)

		assertEquals(listOf(Triple(newer, newest, 10)), coReadPairs())
	}

	@Test
	fun `rebuilding gives the same pairs and drops what no longer qualifies`() {
		val first = work("First")
		val second = work("Second")
		val readers = users("again", 11)
		seen(first, "MANGADEX", "f", readers)
		seen(second, "MANGADEX", "s", readers)

		repository.refreshCoReading()
		val once = coReadPairs()
		repository.refreshCoReading()
		assertEquals(once, coReadPairs(), "a rebuild is idempotent")

		ban(readers[0])
		ban(readers[1])
		repository.refreshCoReading()
		assertTrue(coReadPairs().isEmpty(), "down to nine, so the pair is gone the next day")
	}

	/** Genres alone could never find it: no genre in common. Co-reading can, and the answer lists it. */
	@Test
	fun `a strongly co-read work is suggested even without shared genres`() {
		val seed = work("Seed", genres = listOf("Mecha", "Military"))
		val companion = work("Companion", genres = listOf("Romance", "Comedy"))
		val readers = users("pair", 12)
		seen(seed, "MANGADEX", "seed", readers)
		seen(companion, "MANGADEX", "companion", readers)
		repository.refreshCoReading()

		val similar = repository.similarTo(seed)
		assertEquals(listOf("Companion"), similar.map { it.title })
		assertTrue(similar.single().coReadOnly)

		val response = SimilarWorks(repository).responseFor(seed)
		assertEquals("companion", response.similar.single().sources.single().key, "listed where it can be opened")
	}

	@Test
	fun `co-reading lifts a genre match the same readers opened`() {
		val seed = work("Seed", genres = listOf("Villainess", "Isekai"))
		val shared = work("Shared readers", genres = listOf("Villainess", "Isekai"))
		work("Strangers", genres = listOf("Villainess", "Isekai"))
		val readers = users("lift", 12)
		seen(seed, "MANGADEX", "seed", readers)
		seen(shared, "MANGADEX", "shared", readers)
		repository.refreshCoReading()

		val order = repository.similarTo(seed).map { it.title }

		assertEquals("Shared readers", order.first(), "$order")
	}
}
