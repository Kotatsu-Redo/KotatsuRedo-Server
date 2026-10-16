package io.kotatsuredo.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.routes.SimilarWorksResponse
import io.kotatsuredo.server.works.WorkLinker
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkResolver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Suggestions for an app with no account: `/v1/public/similar`.
 *
 * The privacy notice ("Suggestions, with the community features off") makes three promises this
 * asserts: the lookup needs no credential, it leaves nothing behind - no row anywhere, no manga in a
 * log line - and it only answers for entries several accounts have already established.
 */
class PublicSimilarRoutesTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val works by lazy { WorkRepository(source) }
	private val identities by lazy {
		IdentityService(IdentityRepository(PostgresTestBase.database.exposed, source), DevicePepper.of("test"))
	}
	private val json = Json { ignoreUnknownKeys = true }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE work, work_title, work_alias, work_alias_observation, work_genre_observation, " +
						"work_genre, work_external_id, work_merge_log, app_user, user_active_day, app_device " +
						"CASCADE",
				)
			}
		}
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

	private fun user(seed: String): String =
		(identities.hello(secret(seed), DeviceIdentifiers("dev-$seed", null)) as HelloOutcome.Ok).identity.id

	private fun secret(seed: String) = seed.padEnd(32, '_')

	private fun seen(source: String, key: String, workId: Long, vararg users: String) {
		users.forEach { works.observeAlias(source, key, it, workId, listOf("key")) }
	}

	/** A seed work and one that shares two of its genres, which is what similarity needs. */
	private fun seedAndCandidate(): Pair<Long, Long> {
		val seed = works.createWork("The Seed", 2020, "manhwa", false)
		val candidate = works.createWork("The Candidate", 2021, "manhwa", false)
		works.applyCatalogueGenres(seed, listOf("Villainess", "Romance", "Fantasy"))
		works.applyCatalogueGenres(candidate, listOf("Villainess", "Romance"))
		seen("SOURCE_B", "/candidate", candidate, user("c1"), user("c2"))
		return seed to candidate
	}

	private suspend fun ApplicationTestBuilder.similar(source: String, key: String): HttpResponse =
		client.get("/v1/public/similar?source=${source.encodeURLParameter()}&key=${key.encodeURLParameter()}")

	@Test
	fun `an entry two accounts established is answered without any credential`() = testApplication {
		setup()
		val (seed, candidate) = seedAndCandidate()
		seen("SOURCE_A", "/manga/the-seed", seed, user("a"), user("b"))

		val response = similar("SOURCE_A", "/manga/the-seed")

		assertEquals(HttpStatusCode.OK, response.status)
		assertEquals("public, max-age=21600", response.headers[HttpHeaders.CacheControl])
		val body = json.decodeFromString<SimilarWorksResponse>(response.bodyAsText())
		assertEquals(seed.toString(), body.workId)
		val match = body.similar.single()
		assertEquals(candidate.toString(), match.workId)
		assertEquals(2, match.sharedGenres)
		assertEquals("/candidate", match.sources.single().key, "the app needs the key to open it")
	}

	@Test
	fun `a verified alias is answered on its own`() = testApplication {
		setup()
		val (seed, _) = seedAndCandidate()
		works.linkAlias("SOURCE_A", "/linked", seed, 1.0, "test")

		assertEquals(HttpStatusCode.OK, similar("SOURCE_A", "/linked").status)
	}

	@Test
	fun `an unknown entry is not found`() = testApplication {
		setup()
		seedAndCandidate()

		assertEquals(HttpStatusCode.NotFound, similar("SOURCE_A", "/nobody-has-seen-this").status)
	}

	/** One account's observation is that person's library, never an answer for anybody else. */
	@Test
	fun `an entry only one account has seen is not answered`() = testApplication {
		setup()
		val (seed, _) = seedAndCandidate()
		seen("SOURCE_A", "/solo", seed, user("only"))

		assertEquals(HttpStatusCode.NotFound, similar("SOURCE_A", "/solo").status)
	}

	@Test
	fun `sanctioned accounts do not count towards establishing an entry`() = testApplication {
		setup()
		val (seed, _) = seedAndCandidate()
		val banned = user("banned")
		seen("SOURCE_A", "/contested", seed, user("fine"), banned)
		source.connection.use { connection ->
			connection.prepareStatement("UPDATE app_user SET is_banned = TRUE WHERE id = ?").use {
				it.setString(1, banned)
				it.executeUpdate()
			}
		}

		assertEquals(HttpStatusCode.NotFound, similar("SOURCE_A", "/contested").status)
	}

	@Test
	fun `a merged work answers as the work it was merged into`() = testApplication {
		setup()
		val (seed, _) = seedAndCandidate()
		val absorbed = works.createWork("The Seed (dup)", 2020, "manhwa", false)
		seen("SOURCE_A", "/old-entry", absorbed, user("a"), user("b"))
		source.connection.use { connection ->
			connection.prepareStatement("UPDATE work SET merged_into = ? WHERE id = ?").use {
				it.setLong(1, seed)
				it.setLong(2, absorbed)
				it.executeUpdate()
			}
		}

		val response = similar("SOURCE_A", "/old-entry")

		assertEquals(HttpStatusCode.OK, response.status)
		assertEquals(seed.toString(), json.decodeFromString<SimilarWorksResponse>(response.bodyAsText()).workId)
	}

	@Test
	fun `the lookup leaves nothing behind`() = testApplication {
		setup()
		val (seed, _) = seedAndCandidate()
		seen("SOURCE_A", "/manga/the-seed", seed, user("a"), user("b"))
		val tables = listOf("work", "work_alias", "work_alias_observation", "work_genre_observation", "app_user")
		val before = rowCounts(tables)

		similar("SOURCE_A", "/manga/the-seed")
		similar("SOURCE_A", "/manga/the-seed")
		similar("SOURCE_C", "/never-seen")
		similar("LOCAL", "/sdcard/manga.cbz")
		client.get("/v1/public/similar?source=SOURCE_A&key=/manga/the-seed") {
			// Sent anyway, by an app that has an account: it must neither be required nor used.
			header(HttpHeaders.Authorization, "Bearer ${secret("someone")}")
		}

		assertEquals(before, rowCounts(tables), "an anonymous lookup must not write anything")
	}

	@Test
	fun `malformed lookups are refused`() = testApplication {
		setup()

		assertEquals(HttpStatusCode.BadRequest, client.get("/v1/public/similar?source=SOURCE_A").status)
		assertEquals(HttpStatusCode.BadRequest, client.get("/v1/public/similar?key=/x").status)
		assertEquals(HttpStatusCode.BadRequest, similar("NOT A SOURCE", "/x").status)
		assertEquals(HttpStatusCode.BadRequest, similar("SOURCE_A", "x".repeat(513)).status)
		assertEquals(HttpStatusCode.BadRequest, similar("SOURCE_A", "line\nbreak").status)
		assertEquals(HttpStatusCode.BadRequest, similar("LOCAL", "/sdcard/manga.cbz").status)
	}

	@Test
	fun `one network is limited`() = testApplication {
		setup()

		// 60 a minute per account-equivalent, times ten for a network: the 601st inside a minute is refused.
		val statuses = (1..601).map { similar("SOURCE_A", "/unknown-$it").status }

		assertTrue(statuses.take(600).all { it == HttpStatusCode.NotFound }, "under the limit: ${statuses.toSet()}")
		assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
	}

	@Test
	fun `the manga asked about never reaches a log line`() = testApplication {
		setup()
		val (seed, _) = seedAndCandidate()
		seen("SOURCE_A", KEY_SECRET, seed, user("a"), user("b"))
		val appender = ListAppender<ILoggingEvent>().apply { start() }
		val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
		root.addAppender(appender)
		try {
			similar("SOURCE_A", KEY_SECRET)
			similar("SOURCE_A", "$KEY_SECRET-unknown")
		} finally {
			root.detachAppender(appender)
			appender.stop()
		}
		val logged = appender.list.joinToString("\n") { it.formattedMessage }

		assertTrue("/v1/public/similar" in logged, "expected the request to be logged at all, got: $logged")
		assertFalse(KEY_SECRET in logged, "the entry's key leaked into the log:\n$logged")
	}

	/** The refactor that shares one builder must not have changed what the account route answers. */
	@Test
	fun `the account route answers the same`() = testApplication {
		setup()
		val (seed, _) = seedAndCandidate()
		seen("SOURCE_A", "/manga/the-seed", seed, user("a"), user("b"))
		user("reader")

		val viaAccount = client.get("/v1/works/$seed/similar") {
			header(HttpHeaders.Authorization, "Bearer ${secret("reader")}")
		}
		val anonymous = similar("SOURCE_A", "/manga/the-seed")

		assertEquals(HttpStatusCode.OK, viaAccount.status)
		assertEquals("private, max-age=21600", viaAccount.headers[HttpHeaders.CacheControl])
		assertEquals(
			json.decodeFromString<SimilarWorksResponse>(anonymous.bodyAsText()),
			json.decodeFromString<SimilarWorksResponse>(viaAccount.bodyAsText()),
		)
	}

	private fun rowCounts(tables: List<String>): Map<String, Long> = source.connection.use { connection ->
		connection.createStatement().use { statement ->
			tables.associateWith { table ->
				statement.executeQuery("SELECT count(*) FROM $table").use { rows -> rows.next(); rows.getLong(1) }
			}
		}
	}

	private companion object {
		/** Distinctive so a partial leak still trips the assertion. */
		const val KEY_SECRET = "/manga/zzsecretmangakeyzz-0011223344"
	}
}
