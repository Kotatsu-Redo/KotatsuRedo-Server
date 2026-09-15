package io.kotatsuredo.server

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.comments.CommentService
import io.kotatsuredo.server.comments.PostResult
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.Identity
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.ratings.RatingRepository
import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.routes.ExportDto
import io.kotatsuredo.server.works.WorkRepository
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The right to a copy of your own data, which this service can only satisfy self-service.
 *
 * The server cannot identify anybody, so an access request over email has no way to prove whose
 * account is whose. Holding the key is the only proof there is - which makes the export an endpoint
 * rather than a process, and makes it authenticated exactly like the delete button already is.
 */
class ExportRoutesTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val commentRepository by lazy { CommentRepository(source) }
	private val comments by lazy { CommentService(commentRepository) }
	private val identityRepository by lazy { IdentityRepository(PostgresTestBase.database.exposed, source) }
	private val identities by lazy { IdentityService(identityRepository, DevicePepper.of("test")) }
	private val works by lazy { WorkRepository(source) }
	private val ratings by lazy { RatingService(RatingRepository(source)) }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE comment, comment_vote, rating, work_rating_agg, work, work_title, " +
						"work_alias, work_cover_hash, work_external_id, work_relation, work_merge_log, " +
						"app_user, user_active_day, app_device, device_ban, ban_evasion_flag CASCADE",
				)
			}
		}
	}

	private fun user(seed: String): Identity =
		(identities.hello(testSecret(seed), DeviceIdentifiers("dev-$seed", null)) as HelloOutcome.Ok).identity

	private fun ApplicationTestBuilder.setup() {
		application {
			module(
				health = { true },
				identities = identities,
				comments = comments,
				commentsRepository = commentRepository,
				ratings = ratings,
				works = works,
				limiter = RateLimiter(),
			)
		}
	}

	@Test
	fun `the export contains everything the user wrote`() = testApplication {
		setup()
		val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

		val me = user("mine")
		val other = user("other")
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)
		identities.setNickname(me.id, "reader")

		val mine = assertIs<PostResult.Posted>(
			comments.post(workId, null, me, null, "A comment I wrote about this work.", false, "en"),
		).view.comment
		val theirs = assertIs<PostResult.Posted>(
			comments.post(workId, null, other, null, "A comment somebody else wrote here.", false, "en"),
		).view.comment
		comments.vote(theirs.id, me, 1)
		ratings.rate(workId, me.id, 8)

		val response = client.get("/v1/identity/export") {
			header(HttpHeaders.Authorization, "Bearer ${testSecret("mine")}")
		}
		assertEquals(HttpStatusCode.OK, response.status)
		// Saved as a file rather than rendered.
		assertTrue(
			response.headers[HttpHeaders.ContentDisposition]?.contains("attachment") == true,
			"the export should download rather than render",
		)

		val export = response.body<ExportDto>()
		assertEquals(me.id, export.userId)
		assertEquals("reader", export.nickname)

		assertEquals(listOf(mine.id.toString()), export.comments.map { it.id }, "wrong comments exported")
		assertEquals(4.0, export.ratings.single().stars, "half-stars should be converted for display")
		assertEquals(theirs.id.toString(), export.votes.single().commentId)
		assertEquals(1, export.votes.single().value)
	}

	@Test
	fun `the export crosses a storage page without dropping records`() = testApplication {
		setup()
		val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
		val me = user("paged")
		val workId = works.createWork("Long-running thread", 2020, "manga", nsfw = false)

		source.connection.use { connection ->
			connection.prepareStatement(
				"INSERT INTO comment (work_id, origin_work_id, user_id, body) " +
					"SELECT ?, ?, ?, 'export row ' || n FROM generate_series(1, 251) n",
			).use {
				it.setLong(1, workId)
				it.setLong(2, workId)
				it.setString(3, me.id)
				it.executeUpdate()
			}
		}

		val export = client.get("/v1/identity/export") {
			header(HttpHeaders.Authorization, "Bearer ${testSecret("paged")}")
		}.body<ExportDto>()
		assertEquals(251, export.comments.size)
		assertEquals(251, export.comments.map { it.id }.distinct().size)
	}

	@Test
	fun `the export is only ever your own data`() = testApplication {
		setup()
		val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

		val me = user("mine")
		val other = user("other")
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)
		comments.post(workId, null, other, null, "Something the other person wrote.", false, "en")
		ratings.rate(workId, other.id, 10)

		val export = client.get("/v1/identity/export") {
			header(HttpHeaders.Authorization, "Bearer ${testSecret("mine")}")
		}.body<ExportDto>()

		assertTrue(export.comments.isEmpty(), "someone else's comment appeared in the export")
		assertTrue(export.ratings.isEmpty(), "someone else's rating appeared in the export")
		assertNotNull(me)
	}

	/**
	 * A copy that quietly omits what a moderator removed is not a copy - and it is exactly the part
	 * somebody exercising this right is most likely to be asking about.
	 */
	@Test
	fun `removed and shadowed comments are included`() = testApplication {
		setup()
		val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

		val me = user("mine")
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)
		val kept = assertIs<PostResult.Posted>(
			comments.post(workId, null, me, null, "One that is still standing.", false, "en"),
		).view.comment
		val gone = assertIs<PostResult.Posted>(
			comments.post(workId, null, me, null, "One that was taken down later.", false, "en"),
		).view.comment
		commentRepository.setState(gone.id, io.kotatsuredo.server.comments.CommentState.REMOVED)

		val export = client.get("/v1/identity/export") {
			header(HttpHeaders.Authorization, "Bearer ${testSecret("mine")}")
		}.body<ExportDto>()

		assertEquals(2, export.comments.size, "a removed comment was omitted from the export")
		assertEquals(
			setOf("visible", "removed"),
			export.comments.map { it.state }.toSet(),
			"the export should say which state each comment is in",
		)
		assertNotNull(kept)
	}

	@Test
	fun `the export says what it cannot contain`() = testApplication {
		setup()
		val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
		user("mine")

		val export = client.get("/v1/identity/export") {
			header(HttpHeaders.Authorization, "Bearer ${testSecret("mine")}")
		}.body<ExportDto>()

		// Telemetry genuinely cannot be exported - its pseudonym is derived from a key the server
		// never stores - and saying so is better than an empty field somebody has to interpret.
		val notes = export.notes.joinToString(" ").lowercase()
		assertTrue(notes.contains("telemetry"), "the export should explain why telemetry is absent")
		assertTrue(notes.contains("key"), "the export should explain that the key is not in it")
	}

	@Test
	fun `no credential means no export`() = testApplication {
		setup()
		assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/identity/export").status)
	}

	@Test
	fun `a banned account cannot export`() = testApplication {
		setup()
		val banned = user("banned")
		identityRepository.setBanned(banned.id, banned = true, reason = "test")

		// Consistent with every other endpoint: a ban removes read access, which is the deliberate
		// consequence of identity being all-or-nothing.
		val response = client.get("/v1/identity/export") {
			header(HttpHeaders.Authorization, "Bearer ${testSecret("banned")}")
		}
		assertEquals(HttpStatusCode.Forbidden, response.status)
		assertFalse(response.status.isSuccess())
	}
}

private fun testSecret(seed: String): String = seed.padEnd(32, '_')

private fun HttpStatusCode.isSuccess() = value in 200..299
