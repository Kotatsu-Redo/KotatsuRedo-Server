package io.kotatsuredo.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.comments.CommentState
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.ratings.RatingRepository
import io.kotatsuredo.server.routes.ChartsResponse
import io.kotatsuredo.server.works.WorkLinker
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkResolver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.LoggerFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Top today, this week, this month": `/v1/public/charts`.
 *
 * A public ranking, so what matters most is what it must not count: what people merely opened,
 * sanctioned accounts, comments nobody else can see, and anything only one account did.
 */
class PublicChartsRoutesTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val works by lazy { WorkRepository(source) }
	private val ratings by lazy { RatingRepository(source) }
	private val comments by lazy { CommentRepository(source) }
	private val identities by lazy {
		IdentityService(IdentityRepository(PostgresTestBase.database.exposed, source), DevicePepper.of("test"))
	}
	private val json = Json { ignoreUnknownKeys = true }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		sql(
			"TRUNCATE work, work_title, work_alias, work_alias_observation, work_genre_observation, " +
				"work_genre, work_external_id, work_merge_log, app_user, user_active_day, app_device " +
				"CASCADE",
		)
	}

	private fun ApplicationTestBuilder.setup() {
		application {
			module(
				health = { true },
				identities = identities,
				resolver = WorkResolver(works),
				linker = WorkLinker(works),
				works = works,
			)
		}
	}

	private fun sql(statement: String, vararg parameters: Any) {
		source.connection.use { connection ->
			connection.prepareStatement(statement).use { prepared ->
				parameters.forEachIndexed { index, value -> prepared.setObject(index + 1, value) }
				prepared.execute()
			}
		}
	}

	private fun user(seed: String): String =
		(identities.hello(seed.padEnd(32, '_'), DeviceIdentifiers("dev-$seed", null)) as HelloOutcome.Ok).identity.id

	/** A work two accounts have seen on one source, which is what makes it openable. */
	private fun listedWork(title: String, type: String? = "manhwa", nsfw: Boolean = false): Long {
		val id = works.createWork(title, 2020, type, nsfw)
		listOf("seen-1", "seen-2").forEach { works.observeAlias("SOURCE_A", "/$id", user(it), id, listOf("key")) }
		return id
	}

	private fun rate(work: Long, user: String, value: Int = 8, hoursAgo: Int = 1) {
		ratings.setAndAggregate(work, user, value)
		sql(
			"UPDATE rating SET created_at = now() - make_interval(hours => ?), " +
				"updated_at = now() - make_interval(hours => ?) WHERE work_id = ? AND user_id = ?",
			hoursAgo, hoursAgo, work, user,
		)
	}

	private fun comment(
		work: Long,
		user: String,
		hoursAgo: Int = 1,
		state: CommentState = CommentState.VISIBLE,
		deleted: Boolean = false,
	) {
		val id = comments.insert(work, null, user, null, 0, "a comment", false, "en", state)
		sql("UPDATE comment SET created_at = now() - make_interval(hours => ?) WHERE id = ?", hoursAgo, id)
		if (deleted) sql("UPDATE comment SET deleted_at = now() WHERE id = ?", id)
	}

	/** Two accounts' worth of activity, the least that lists a work. */
	private fun active(work: Long, hoursAgo: Int = 1) {
		rate(work, user("active-1"), hoursAgo = hoursAgo)
		rate(work, user("active-2"), hoursAgo = hoursAgo)
	}

	private fun setBayesian(work: Long, bayesian: Double) {
		sql("UPDATE work_rating_agg SET bayesian = ? WHERE work_id = ?", bayesian, work)
	}

	private suspend fun ApplicationTestBuilder.charts(query: String): HttpResponse =
		client.get("/v1/public/charts?$query")

	private suspend fun ApplicationTestBuilder.chart(query: String): ChartsResponse {
		val response = charts(query)
		assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
		return json.decodeFromString(response.bodyAsText())
	}

	private suspend fun ApplicationTestBuilder.ids(query: String): List<Long> =
		chart(query).works.map { it.workId.toLong() }

	@Test
	fun `the answer has the agreed shape and needs no credential`() = testApplication {
		setup()
		val work = listedWork("The Work", type = "manhwa")
		works.applyCatalogueGenres(work, listOf("Romance", "Villainess"))
		rate(work, user("a"))
		comment(work, user("b"))
		setBayesian(work, 8.5)

		val before = System.currentTimeMillis()
		val response = charts("window=week")

		assertEquals(HttpStatusCode.OK, response.status)
		assertEquals("public, max-age=900", response.headers[HttpHeaders.CacheControl])
		val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
		assertEquals(setOf("window", "generated_at", "works"), body.keys)
		assertEquals("week", body.getValue("window").jsonPrimitive.content)
		assertTrue(body.getValue("generated_at").jsonPrimitive.long >= before)
		val entry = body.getValue("works").jsonArray.single() as JsonObject
		assertEquals(
			setOf("work_id", "title", "year", "content_type", "nsfw", "genres", "shared_genres", "score", "sources"),
			entry.keys,
		)
		val source = entry.getValue("sources").jsonArray.single().jsonObject
		assertEquals(setOf("source", "key", "reporters", "last_seen", "verified"), source.keys)

		val parsed = chart("window=week").works.single()
		assertEquals(work.toString(), parsed.workId)
		assertEquals(0, parsed.sharedGenres)
		assertEquals(setOf("romance", "villainess"), parsed.genres.toSet())
		assertEquals(2, parsed.sources.single().reporters)
		assertEquals("/$work", parsed.sources.single().key, "the app needs the key to open it")
	}

	@Test
	fun `the score is the agreed formula and the order is by score then id`() = testApplication {
		setup()
		// activity 2*2 + 1 = 5, quality 8 -> 4.0
		val rated = listedWork("Rated")
		rate(rated, user("a"))
		rate(rated, user("b"))
		comment(rated, user("c"))
		setBayesian(rated, 8.0)
		// Commented only, so no aggregate: activity 2, quality 7.0 -> 1.4
		val commented = listedWork("Commented")
		comment(commented, user("a"))
		comment(commented, user("b"))
		// Rated, then cleared: the aggregate row survives with a Bayesian of zero that must not count.
		val cleared = listedWork("Cleared")
		ratings.setAndAggregate(cleared, user("d"), 9)
		ratings.deleteAndAggregate(cleared, user("d"))
		comment(cleared, user("a"))
		comment(cleared, user("b"))
		// Rating and commenting are both counted, for the same account: activity 2*2 + 2 = 6, quality 5 -> 3.0
		val both = listedWork("Both")
		rate(both, user("a"))
		rate(both, user("b"))
		comment(both, user("a"))
		comment(both, user("b"))
		comment(both, user("b"))
		setBayesian(both, 5.0)

		val chart = chart("window=day").works

		assertEquals(listOf(rated, both, commented, cleared).map(Long::toString), chart.map { it.workId })
		assertEquals(listOf(4.0, 3.0, 1.4, 1.4), chart.map { it.score })
	}

	@Test
	fun `activity outside the window does not count`() = testApplication {
		setup()
		val inDay = listedWork("Twenty three hours ago")
		active(inDay, hoursAgo = 23)
		val inWeek = listedWork("Twenty five hours ago")
		active(inWeek, hoursAgo = 25)
		val weekEdge = listedWork("Six days ago")
		comment(weekEdge, user("a"), hoursAgo = 6 * 24)
		comment(weekEdge, user("b"), hoursAgo = 6 * 24)
		val inMonth = listedWork("Eight days ago")
		active(inMonth, hoursAgo = 8 * 24)
		val monthEdge = listedWork("Twenty nine days ago")
		comment(monthEdge, user("a"), hoursAgo = 29 * 24)
		comment(monthEdge, user("b"), hoursAgo = 29 * 24)
		val tooOld = listedWork("Thirty one days ago")
		active(tooOld, hoursAgo = 31 * 24)
		comment(tooOld, user("a"), hoursAgo = 31 * 24)
		comment(tooOld, user("b"), hoursAgo = 31 * 24)

		assertEquals(setOf(inDay), ids("window=day").toSet())
		assertEquals(setOf(inDay, inWeek, weekEdge), ids("window=week").toSet())
		assertEquals(setOf(inDay, inWeek, weekEdge, inMonth, monthEdge), ids("window=month").toSet())
	}

	@Test
	fun `changing an old rating is activity and an old comment is not`() = testApplication {
		setup()
		val changed = listedWork("Changed")
		val a = user("a")
		val b = user("b")
		rate(changed, a, hoursAgo = 60 * 24)
		rate(changed, b, hoursAgo = 60 * 24)
		sql("UPDATE rating SET updated_at = now() - interval '1 hour' WHERE work_id = ?", changed)
		val edited = listedWork("Edited")
		comment(edited, a, hoursAgo = 60 * 24)
		comment(edited, b, hoursAgo = 60 * 24)
		sql("UPDATE comment SET edited_at = now() WHERE work_id = ?", edited)

		assertEquals(listOf(changed), ids("window=day"))
	}

	@Test
	fun `one account is not enough, however active`() = testApplication {
		setup()
		val solo = listedWork("Solo")
		val a = user("a")
		rate(solo, a)
		comment(solo, a)
		comment(solo, a)
		val two = listedWork("Two")
		rate(two, a)
		comment(two, user("b"))

		assertEquals(listOf(two), ids("window=week"))
	}

	/** The privacy notice: which manga you opened is never shown to anyone. */
	@Test
	fun `having opened a work is not activity`() = testApplication {
		setup()
		val opened = listedWork("Opened by many")
		(1..10).forEach { works.observeAlias("SOURCE_B", "/$opened", user("reader-$it"), opened, listOf("key")) }
		source.connection.use { connection ->
			connection.prepareStatement("INSERT INTO work_genre_observation (work_id, genre, user_id) VALUES (?, 'romance', ?)").use {
				it.setLong(1, opened)
				it.setString(2, user("reader-1"))
				it.executeUpdate()
			}
		}

		assertEquals(emptyList(), ids("window=month"))
	}

	@Test
	fun `sanctioned accounts count for nothing`() = testApplication {
		setup()
		val shadowed = user("shadowed")
		val banned = user("banned")
		sql("UPDATE app_user SET is_shadowbanned = TRUE WHERE id = ?", shadowed)
		sql("UPDATE app_user SET is_banned = TRUE WHERE id = ?", banned)
		val fine = user("fine")

		val shadowRated = listedWork("Shadow rated")
		rate(shadowRated, shadowed)
		rate(shadowRated, fine)
		// Written before the shadowban landed, so the comment itself is still visible.
		val shadowCommented = listedWork("Shadow commented")
		comment(shadowCommented, shadowed)
		comment(shadowCommented, fine)
		val banRated = listedWork("Ban rated")
		rate(banRated, banned)
		rate(banRated, fine)
		val counted = listedWork("Counted")
		rate(counted, fine)
		rate(counted, user("fine-2"))
		rate(counted, shadowed)
		comment(counted, banned)
		setBayesian(counted, 5.0)

		val chart = chart("window=week").works

		assertEquals(listOf(counted.toString()), chart.map { it.workId })
		assertEquals(2.0, chart.single().score, "only the two fine raters: 2*2 * 5 / 10")
	}

	@Test
	fun `comments nobody else can see count for nothing`() = testApplication {
		setup()
		val fine = user("fine")
		val shadowedComment = listedWork("Shadowed comment")
		comment(shadowedComment, fine)
		comment(shadowedComment, user("other"), state = CommentState.SHADOWED)
		val removedComment = listedWork("Removed comment")
		comment(removedComment, fine)
		comment(removedComment, user("other"), state = CommentState.REMOVED)
		val deletedComment = listedWork("Deleted comment")
		comment(deletedComment, fine)
		comment(deletedComment, user("other"), deleted = true)
		val erasedAccount = listedWork("Erased account")
		comment(erasedAccount, fine)
		val gone = user("gone")
		comment(erasedAccount, gone)
		sql("UPDATE comment SET user_id = NULL WHERE user_id = ?", gone)

		assertEquals(emptyList(), ids("window=week"))
	}

	@Test
	fun `nothing adult is listed, whatever is asked`() = testApplication {
		setup()
		val safe = listedWork("Safe")
		val flagged = listedWork("Flagged", nsfw = true)
		val hentaiType = listedWork("Hentai source", type = "hentai")
		val adultGenre = listedWork("Tagged adult")
		val ecchiGenre = listedWork("Tagged ecchi")
		works.applyCatalogueGenres(adultGenre, listOf("Smut"))
		works.applyCatalogueGenres(ecchiGenre, listOf("Ecchi"))
		listOf(safe, flagged, hentaiType, adultGenre, ecchiGenre).forEach { active(it) }

		assertEquals(listOf(safe), ids("window=week"))
		assertEquals(listOf(safe), ids("window=week&nsfw=true"), "the old parameter must not open anything up")
		assertEquals(emptyList(), ids("window=week&genre=adult"))
		assertEquals(emptyList(), ids("window=week&genre=hentai"), "an alias of adult")
		assertEquals(emptyList(), ids("window=week&genre=ecchi"))
	}

	/** A merge does not carry the flag across, so the survivor looks clean on its own. */
	@Test
	fun `a work that absorbed a flagged one is not listed`() = testApplication {
		setup()
		val survivor = listedWork("Survivor")
		val middle = works.createWork("Middle", 2020, "manhwa", false)
		val flagged = works.createWork("Flagged", 2020, "manhwa", true)
		sql("UPDATE work SET merged_into = ? WHERE id = ?", middle, flagged)
		sql("UPDATE work SET merged_into = ? WHERE id = ?", survivor, middle)
		active(survivor)

		assertEquals(emptyList(), ids("window=week"))
	}

	@Test
	fun `the type filter matches the lower-case type exactly`() = testApplication {
		setup()
		val manga = listedWork("Manga", type = "manga")
		val manhwa = listedWork("Manhwa", type = "manhwa")
		val manhua = listedWork("Manhua", type = "manhua")
		val untyped = listedWork("Untyped", type = null)
		val other = listedWork("Novel", type = "novel")
		val shouting = listedWork("Older client", type = "MANGA")
		listOf(manga, manhwa, manhua, untyped, other, shouting).forEach { active(it) }

		assertEquals(setOf(manga, shouting), ids("window=week&type=manga").toSet())
		assertEquals(setOf(manhwa), ids("window=week&type=manhwa").toSet())
		assertEquals(setOf(manhua), ids("window=week&type=manhua").toSet())
		assertEquals(6, ids("window=week").size)
	}

	@Test
	fun `the genre filter goes through the vocabulary`() = testApplication {
		setup()
		val romance = listedWork("Romance")
		val girlsLove = listedWork("Girls love")
		works.applyCatalogueGenres(romance, listOf("Romance"))
		works.applyCatalogueGenres(girlsLove, listOf("Girls Love"))
		active(romance)
		active(girlsLove)

		assertEquals(listOf(romance), ids("window=week&genre=romance"))
		assertEquals(listOf(romance), ids("window=week&genre=Romance"))
		assertEquals(listOf(girlsLove), ids("window=week&genre=girls-love"))
		assertEquals(listOf(girlsLove), ids("window=week&genre=yuri"), "an alias means the canonical term")
		assertEquals(emptyList(), ids("window=week&genre=cooking"))
	}

	/** An unknown label is dropped everywhere else; here that leaves nothing that could carry it. */
	@Test
	fun `a genre outside the vocabulary is an empty chart`() = testApplication {
		setup()
		active(listedWork("Anything"))

		val response = charts("window=week&genre=not-a-genre")

		assertEquals(HttpStatusCode.OK, response.status)
		assertEquals("public, max-age=900", response.headers[HttpHeaders.CacheControl])
		assertEquals(emptyList(), json.decodeFromString<ChartsResponse>(response.bodyAsText()).works)
	}

	@Test
	fun `a merged work is never listed and its activity moves with it`() = testApplication {
		setup()
		val survivor = listedWork("Survivor")
		val absorbed = listedWork("Absorbed")
		// One rater on each, plus one who rated both and must count once. The commenter's row moves too.
		rate(survivor, user("survivor-only"))
		rate(survivor, user("both"))
		rate(absorbed, user("both"))
		rate(absorbed, user("absorbed-only"))
		comment(absorbed, user("commenter"))
		works.mergeWorks(absorbed, survivor, "test")
		setBayesian(survivor, 5.0)

		val chart = chart("window=week").works

		assertEquals(listOf(survivor.toString()), chart.map { it.workId })
		assertEquals(3.5, chart.single().score, "three raters and a commenter: (2*3 + 1) * 5 / 10")
	}

	/** A collision leaves the rating behind, so only following the chain finds where it belongs. */
	@Test
	fun `a rating left behind by a merge is attributed along the whole chain`() = testApplication {
		setup()
		val first = listedWork("First")
		val middle = listedWork("Middle")
		val last = listedWork("Last")
		val both = user("both")
		// Rated first recently and middle long ago, so only the row a merge cannot move is in the window.
		rate(first, both, hoursAgo = 1)
		rate(middle, both, hoursAgo = 60 * 24)
		rate(middle, user("other"), hoursAgo = 1)
		works.mergeWorks(first, middle, "test")
		works.mergeWorks(middle, last, "test")

		assertEquals(listOf(last), ids("window=week"))
	}

	@Test
	fun `a work no source is corroborated for is left out, without shortening the chart`() = testApplication {
		setup()
		val never = works.createWork("Never seen", 2020, "manhwa", false)
		// Two accounts, but on two sources: neither source has two reporters.
		val split = works.createWork("Split", 2020, "manhwa", false)
		works.observeAlias("SOURCE_A", "/split", user("x"), split, listOf("key"))
		works.observeAlias("SOURCE_B", "/split", user("y"), split, listOf("key"))
		// Both have the highest score there is here, so they would fill a chart cut before filtering.
		listOf(never, split).forEach {
			active(it)
			setBayesian(it, 10.0)
		}
		val openable = listedWork("Openable")
		active(openable)
		setBayesian(openable, 1.0)

		assertEquals(listOf(openable), ids("window=week&limit=1"))
	}

	@Test
	fun `the limit is applied and defaults to thirty`() = testApplication {
		setup()
		val all = (1..32).map { listedWork("Work $it") }
		all.forEach { active(it) }

		assertEquals(30, ids("window=week").size)
		assertEquals(all.take(3), ids("window=week&limit=3"))
		assertEquals(1, ids("window=week&limit=1").size)
		assertEquals(32, ids("window=week&limit=50").size)
	}

	@Test
	fun `bad parameters are refused in the usual format`() = testApplication {
		setup()

		mapOf(
			"" to "window",
			"window=" to "window",
			"window=year" to "window",
			"window=WEEK" to "window",
			"window=week&type=novel" to "type",
			"window=week&type=" to "type",
			"window=week&type=MANGA" to "type",
			"window=week&limit=0" to "limit",
			"window=week&limit=201" to "limit",
			"window=week&limit=-1" to "limit",
			"window=week&limit=ten" to "limit",
			"window=week&limit=" to "limit",
		).forEach { (query, field) ->
			val response = charts(query)
			assertEquals(HttpStatusCode.BadRequest, response.status, query)
			assertEquals(null, response.headers[HttpHeaders.CacheControl], query)
			val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject
			assertTrue(field in error.toString(), "$query answered $error")
		}
	}

	@Test
	fun `a chart the app already has costs no content`() = testApplication {
		setup()
		active(listedWork("Work"))

		val first = charts("window=week")
		val tag = assertNotNull(first.headers[HttpHeaders.ETag])
		assertTrue(first.bodyAsText().isNotEmpty())

		val again = client.get("/v1/public/charts?window=week") { header(HttpHeaders.IfNoneMatch, tag) }
		assertEquals(HttpStatusCode.NotModified, again.status)
		assertEquals("", again.bodyAsText())

		// A proxy that compresses the answer changes how the tag looks, not what it says
		val reworded = client.get("/v1/public/charts?window=week") { header(HttpHeaders.IfNoneMatch, "W/$tag") }
		assertEquals(HttpStatusCode.NotModified, reworded.status)

		val outdated = client.get("/v1/public/charts?window=week") { header(HttpHeaders.IfNoneMatch, "\"1\"") }
		assertEquals(HttpStatusCode.OK, outdated.status)
	}

	@Test
	fun `a whole chart can be asked for at once`() = testApplication {
		setup()
		(1..60).forEach { active(listedWork("Work $it")) }

		assertEquals(60, ids("window=week&limit=200").size)
	}

	@Test
	fun `a chart is kept rather than rebuilt`() = testApplication {
		setup()
		val first = listedWork("First")
		active(first)
		val generated = chart("window=week").generatedAt

		active(listedWork("Second"))

		val again = chart("window=week")
		assertEquals(listOf(first.toString()), again.works.map { it.workId })
		assertEquals(generated, again.generatedAt)
	}

	@Test
	fun `a lookup leaves nothing behind`() = testApplication {
		setup()
		active(listedWork("Work"))
		val tables = listOf("work", "work_alias", "work_alias_observation", "work_genre_observation", "app_user", "rating", "comment")
		val before = rowCounts(tables)

		chart("window=week")
		chart("window=day&type=manga&genre=romance&limit=5")

		assertEquals(before, rowCounts(tables))
	}

	@Test
	fun `what was asked for never reaches a log line`() = testApplication {
		setup()
		val appender = ListAppender<ILoggingEvent>().apply { start() }
		val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
		root.addAppender(appender)
		try {
			chart("window=week&genre=villainess")
		} finally {
			root.detachAppender(appender)
			appender.stop()
		}
		val logged = appender.list.joinToString("\n") { it.formattedMessage }

		assertTrue("/v1/public/charts" in logged, "expected the request to be logged at all, got: $logged")
		assertFalse("villainess" in logged, "the query leaked into the log:\n$logged")
	}

	@Test
	fun `one network is limited`() = testApplication {
		setup()

		// The same allowance as the similar lookup: 60 a minute, times ten for a network.
		val statuses = (1..601).map { charts("window=week&genre=not-a-genre").status }

		assertTrue(statuses.take(600).all { it == HttpStatusCode.OK }, "under the limit: ${statuses.toSet()}")
		assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
	}

	private fun rowCounts(tables: List<String>): Map<String, Long> = source.connection.use { connection ->
		connection.createStatement().use { statement ->
			tables.associateWith { table ->
				statement.executeQuery("SELECT count(*) FROM $table").use { rows -> rows.next(); rows.getLong(1) }
			}
		}
	}
}
