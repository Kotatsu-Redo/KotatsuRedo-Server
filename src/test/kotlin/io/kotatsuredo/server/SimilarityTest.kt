package io.kotatsuredo.server

import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.routes.SimilarWorks
import io.kotatsuredo.server.works.CoverUrls
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

	// -- covers ------------------------------------------------------------------------------------

	/** Past the new tier: more than a day old and active on three days. */
	private fun established(prefix: String, count: Int) = users(prefix, count).onEach { settled(it) }

	private fun settled(userId: String) {
		sql("UPDATE app_user SET created_at = now() - interval '2 days' WHERE id = '$userId'")
		sql(
			"INSERT INTO user_active_day (user_id, day) " +
				"SELECT '$userId', CURRENT_DATE - n FROM generate_series(1, 3) n ON CONFLICT DO NOTHING",
		)
	}

	@Test
	fun `a cover is only served once enough established accounts report the same address`() {
		val (a, b, c) = established("cover", 3)
		val dissenter = established("cover-other", 1).single()
		val id = work("Covered")
		seen(id, "MANGADEX", "x1", listOf(a, b, c, dissenter))
		val url = "https://cdn.example.org/x1.jpg"

		repository.observeCover("MANGADEX", "x1", a, url)
		assertEquals(null, repository.sourcesForAll(listOf(id), 5)[id]?.single()?.coverUrl, "one account is not agreement")

		repository.observeCover("MANGADEX", "x1", b, url)
		assertEquals(null, repository.sourcesForAll(listOf(id), 5)[id]?.single()?.coverUrl, "nor are two")

		repository.observeCover("MANGADEX", "x1", c, "https://evil.example.net/other.jpg")
		assertEquals(null, repository.sourcesForAll(listOf(id), 5)[id]?.single()?.coverUrl, "they have to agree exactly")

		repository.observeCover("MANGADEX", "x1", c, url)
		assertEquals(url, repository.sourcesForAll(listOf(id), 5)[id]?.single()?.coverUrl)

		repository.observeCover("MANGADEX", "x1", dissenter, "https://evil.example.net/other.jpg")
		assertEquals(url, repository.sourcesForAll(listOf(id), 5)[id]?.single()?.coverUrl, "the lone dissenter loses")
	}

	@Test
	fun `new and banned accounts do not count towards a cover`() {
		val (a, b, c) = established("cover-old", 3)
		val fresh = user("cover-new")
		val aged = user("cover-aged")
		sql("UPDATE app_user SET created_at = now() - interval '30 days' WHERE id = '$aged'")
		val id = work("Contested")
		seen(id, "MANGADEX", "x1", listOf(a, b, c, fresh, aged))
		val url = "https://cdn.example.org/x1.jpg"

		repository.observeCover("MANGADEX", "x1", a, url)
		repository.observeCover("MANGADEX", "x1", b, url)
		repository.observeCover("MANGADEX", "x1", fresh, url)
		assertEquals(null, repository.sourcesForAll(listOf(id), 5)[id]?.single()?.coverUrl, "a day-old account is required")

		repository.observeCover("MANGADEX", "x1", aged, url)
		assertEquals(null, repository.sourcesForAll(listOf(id), 5)[id]?.single()?.coverUrl, "so is having actually been around")

		repository.observeCover("MANGADEX", "x1", c, url)
		assertEquals(url, repository.sourcesForAll(listOf(id), 5)[id]?.single()?.coverUrl)

		ban(c)
		assertEquals(null, repository.sourcesForAll(listOf(id), 5)[id]?.firstOrNull()?.coverUrl, "a ban takes the report with it")
	}

	/** Asking for an entry twice used to count each report twice, so one account's word could be enough. */
	@Test
	fun `an entry listed under two works does not count its reports twice`() {
		val (a, b, c) = established("cover-twice", 3)
		val d = established("cover-twice-other", 1).single()
		val first = work("Listed Once")
		val second = work("Listed Again")
		seen(first, "MANGADEX", "x1", listOf(a, b))
		seen(second, "MANGADEX", "x1", listOf(c, d))
		val url = "https://cdn.example.org/x1.jpg"
		repository.observeCover("MANGADEX", "x1", a, url)
		repository.observeCover("MANGADEX", "x1", b, url)

		val sources = repository.sourcesForAll(listOf(first, second), 5)

		assertEquals(null, sources[first]?.single()?.coverUrl, "two accounts are still only two")
		assertEquals(null, sources[second]?.single()?.coverUrl, "two accounts are still only two")
	}

	/**
	 * A cover is an extra. Every suggestion and every chart reads through this query, so a problem with
	 * covers alone must not take the sources, and with them the whole answer, down with it.
	 */
	@Test
	fun `sources are still answered when covers cannot be read`() {
		val (a, b) = established("cover-broken", 2)
		val id = work("Still Listed")
		seen(id, "MANGADEX", "x1", listOf(a, b))

		sql("ALTER TABLE work_cover_observation RENAME TO work_cover_observation_hidden")
		try {
			val source = repository.sourcesForAll(listOf(id), 5)[id]?.single()
			assertEquals("x1", source?.sourceKey)
			assertEquals(null, source?.coverUrl)
		} finally {
			sql("ALTER TABLE work_cover_observation_hidden RENAME TO work_cover_observation")
		}
	}

	@Test
	fun `reporting the same cover again does not rewrite the row`() {
		val reporter = user("cover-again")
		val url = "https://cdn.example.org/x1.jpg"
		repository.observeCover("MANGADEX", "x1", reporter, url)
		sql("UPDATE work_cover_observation SET updated_at = '2020-01-01T00:00:00Z'")

		repository.observeCover("MANGADEX", "x1", reporter, url)
		assertEquals(2020, reportedYear(reporter), "a repeat open costs no write")

		repository.observeCover("MANGADEX", "x1", reporter, "https://cdn.example.org/new.jpg")
		assertTrue(reportedYear(reporter) > 2020, "a new address does")
	}

	private fun reportedYear(userId: String): Int =
		repository.coverObservationsByPage(userId, null, 1).single().updatedAt.year

	@Test
	fun `only a plain public https address is kept as a cover`() {
		assertEquals("https://cdn.example.org/a.jpg?w=300", CoverUrls.clean(" https://cdn.example.org/a.jpg?w=300 "))
		listOf(
			null,
			"",
			"http://cdn.example.org/a.jpg",
			"https://user:pass@cdn.example.org/a.jpg",
			"https://192.168.1.10/a.jpg",
			"https://[::1]/a.jpg",
			"https://localhost/a.jpg",
			"https://nas.local/a.jpg",
			"https://nas.local./a.jpg",
			"https://localhost./a.jpg",
			"https://127.1/a.jpg",
			"https://0x7f.1/a.jpg",
			"https://0x7f.0.0.1/a.jpg",
			"https://10.0.0.1./a.jpg",
			"https://router.home.arpa/a.jpg",
			"https://cdn.example.org:8443/a.jpg",
			"file:///etc/passwd",
			"https://cdn.example.org/a b.jpg",
			"https://cdn.example.org/" + "a".repeat(1100),
		).forEach { assertEquals(null, CoverUrls.clean(it), "$it") }
	}

	// -- what is close, and what is suitable --------------------------------------------------------

	private fun adultWork(title: String, genres: List<String>): Long =
		repository.createWork(title, 2019, "manga", true).also { repository.applyCatalogueGenres(it, genres) }

	@Test
	fun `a work about the same thing beats one tagged with everything`() {
		val seed = work("Seed", genres = listOf("Romance", "Comedy", "School Life"))
		val focused = work("Focused", genres = listOf("Romance", "Comedy", "School Life"))
		val everything = work(
			"Everything",
			genres = listOf("Romance", "Comedy", "School Life", "Action", "Adventure", "Fantasy", "Horror", "Mystery", "Sports", "Drama"),
		)

		val found = repository.similarTo(seed).map { it.workId }
		assertTrue(found.indexOf(focused) in 0 until found.indexOf(everything), "$found")
	}

	@Test
	fun `adult works do not answer for ordinary ones`() {
		val seed = work("Seed", genres = listOf("Romance", "Comedy", "School Life"))
		val fine = work("Fine", genres = listOf("Romance", "Comedy", "School Life"))
		val flagged = adultWork("Flagged", listOf("Romance", "Comedy", "School Life"))
		val taggedAdult = work("Tagged adult", genres = listOf("Romance", "Comedy", "School Life", "Adult"))
		val taggedEcchi = work("Tagged ecchi", genres = listOf("Romance", "Comedy", "School Life", "Ecchi"))

		val found = repository.similarTo(seed).map { it.workId }
		assertTrue(fine in found, "$found")
		assertTrue(flagged !in found && taggedAdult !in found && taggedEcchi !in found, "$found")
	}

	@Test
	fun `someone reading ecchi can be offered ecchi but still not porn`() {
		val seed = work("Seed", genres = listOf("Romance", "Comedy", "Ecchi"))
		val ecchi = work("Ecchi", genres = listOf("Romance", "Comedy", "Ecchi"))
		val adult = work("Adult", genres = listOf("Romance", "Comedy", "Ecchi", "Adult"))

		val found = repository.similarTo(seed).map { it.workId }
		assertTrue(ecchi in found, "$found")
		assertTrue(adult !in found, "$found")
	}

	@Test
	fun `an adult work can be answered with adult works`() {
		val seed = adultWork("Seed", listOf("Romance", "Comedy", "Adult"))
		val flagged = adultWork("Flagged", listOf("Romance", "Comedy", "Adult"))

		assertTrue(flagged in repository.similarTo(seed).map { it.workId })
	}

	@Test
	fun `co-reading does not bring in what genres would have kept out`() {
		val seed = work("Seed", genres = listOf("Romance", "Comedy"))
		val adult = adultWork("Adult", listOf("Horror", "Adult"))
		val readers = users("suit-reader", 12)
		seen(seed, "S1", "s", readers)
		seen(adult, "S1", "a", readers)
		repository.refreshCoReading()

		assertTrue(adult !in repository.similarTo(seed).map { it.workId })
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

	// -- passes ("not interested") --------------------------------------------------------------

	private fun passedBy(workId: Long, users: Collection<String>) {
		users.forEach { repository.setPass(workId, it) }
	}

	/** Two otherwise identical candidates, the first passed on by [passers], both read by twelve accounts. */
	private fun passScores(passers: Collection<String>, readerCount: Int = 12): Pair<Double, Double> {
		val seed = work("Seed", genres = listOf("Villainess", "Isekai"))
		val passed = work("Passed", genres = listOf("Villainess", "Isekai"))
		val control = work("Control", genres = listOf("Villainess", "Isekai"))
		val readers = users("pr", readerCount)
		seen(passed, "MANGADEX", "passed", readers)
		seen(control, "MANGADEX", "control", readers)
		passedBy(passed, passers)
		val scores = repository.similarTo(seed).associate { it.title to it.score }
		return scores.getValue("Passed") to scores.getValue("Control")
	}

	@Test
	fun `below ten passers nothing changes`() {
		val (passed, control) = passScores(established("p", 9))

		assertEquals(control, passed, 1e-12)
	}

	@Test
	fun `ten passers lower a work by the documented share`() {
		val (passed, control) = passScores(established("p", 10))

		// 1 - 0.3 * 10 / (10 + 12) of the same score
		assertEquals(control * (1.0 - 0.3 * 10 / 22), passed, 1e-9)
		assertTrue(passed < control)
	}

	@Test
	fun `more passers lower it further but never by more than thirty percent`() {
		val (passed, control) = passScores(established("p", 40))

		assertEquals(control * (1.0 - 0.3 * 40 / 52), passed, 1e-9)
		assertTrue(passed > control * 0.7)
	}

	@Test
	fun `ten passers with no readers at all lower a work to exactly seventy percent`() {
		val (passed, control) = passScores(established("p", 10), readerCount = 0)

		assertEquals(control * 0.7, passed, 1e-12)
	}

	/** Accounts are free to make, so ten fresh ones must not be able to lower a work. */
	@Test
	fun `passes by brand new accounts are stored but not counted until they are not new`() {
		val passers = users("fresh", 10) // tier 0: younger than a day, no active days
		val seed = work("Seed", genres = listOf("Villainess", "Isekai"))
		val passed = work("Passed", genres = listOf("Villainess", "Isekai"))
		val control = work("Control", genres = listOf("Villainess", "Isekai"))
		passers.forEach { repository.setPass(passed, it) }

		fun ratio(): Double {
			val scores = repository.similarTo(seed).associate { it.title to it.score }
			return scores.getValue("Passed") / scores.getValue("Control")
		}
		assertEquals(1.0, ratio(), 1e-12)

		passers.forEach { settled(it) }
		assertEquals(1.0 - 0.3 * 10 / 10, ratio(), 1e-9)
	}

	@Test
	fun `banned and shadowbanned passers are not counted`() {
		val clean = established("p", 8)
		val banned = established("p-banned", 1).single()
		val shadowbanned = established("p-shadow", 1).single()
		ban(banned)
		sql("UPDATE app_user SET is_shadowbanned = TRUE WHERE id = '$shadowbanned'")

		val (passed, control) = passScores(clean + banned + shadowbanned)

		assertEquals(control, passed, 1e-12)
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

	/** A work an account passed on is not something it read together with the rest. */
	@Test
	fun `a pair is not counted for an account that passed on one of the two`() {
		val first = work("First")
		val second = work("Second")
		val readers = users("cp", 11)
		seen(first, "MANGADEX", "f", readers)
		seen(second, "MANGADEX", "s", readers)

		repository.refreshCoReading()
		assertEquals(listOf(Triple(first, second, 11)), coReadPairs())

		repository.setPass(second, readers[0])
		repository.refreshCoReading()
		assertEquals(listOf(Triple(first, second, 10)), coReadPairs(), "the passer no longer counts")

		repository.setPass(first, readers[1])
		repository.refreshCoReading()
		assertTrue(coReadPairs().isEmpty(), "down to nine")
	}

	@Test
	fun `a pass only removes that work from the account's other pairs`() {
		val first = work("First")
		val second = work("Second")
		val third = work("Third")
		val readers = users("cq", 10)
		seen(first, "MANGADEX", "f", readers)
		seen(second, "MANGADEX", "s", readers)
		seen(third, "MANGADEX", "t", readers)
		repository.setPass(third, readers[0])

		repository.refreshCoReading()

		assertEquals(listOf(Triple(first, second, 10)), coReadPairs())
	}
}
