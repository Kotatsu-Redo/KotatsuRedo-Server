package io.kotatsuredo.server

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.comments.CommentRules
import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.comments.CommentService
import io.kotatsuredo.server.comments.ContentFilter
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.routes.CommentDto
import io.kotatsuredo.server.routes.CommentPageResponse
import io.kotatsuredo.server.routes.NotificationsResponse
import io.kotatsuredo.server.works.WorkRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The comment endpoints over real HTTP.
 *
 * [CommentServiceTest] covers the rules; this covers the wire: status codes, the error codes the app
 * switches on, and the fact that none of it is reachable without a credential.
 */
class CommentRoutesTest {

	private val works by lazy { WorkRepository(PostgresTestBase.database.source) }
	private val identities by lazy {
		IdentityService(IdentityRepository(PostgresTestBase.database.exposed), DevicePepper.of("test"))
	}
	private val comments by lazy {
		CommentService(
			CommentRepository(PostgresTestBase.database.source),
			filter = { body, _, _ ->
				if ("badword" in body) {
					ContentFilter.Verdict.Blocked("badword", "profanity")
				} else {
					ContentFilter.Verdict.Allowed
				}
			},
		)
	}

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE comment, comment_vote, work, work_title, work_alias, work_cover_hash, " +
						"work_external_id, work_relation, work_merge_log, app_user, user_active_day, " +
						"app_device, device_ban, ban_evasion_flag CASCADE",
				)
			}
		}
	}

	/** Registers a secret so it authenticates, and hands the secret back as the bearer token. */
	private fun secret(seed: String): String {
		identities.hello(seed, DeviceIdentifiers("dev-$seed", null))
		return seed
	}

	private fun ApplicationTestBuilder.setup() {
		application {
			module(
				health = { true },
				identities = identities,
				comments = comments,
				works = works,
				limiter = RateLimiter(),
				rulesUrl = "https://example.invalid/rules",
			)
		}
	}

	private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
		install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
	}

	private suspend fun HttpClient.postComment(
		workId: Long,
		token: String,
		body: String,
		parentId: String? = null,
	): HttpResponse = post("/v1/works/$workId/comments") {
		header(HttpHeaders.Authorization, "Bearer $token")
		contentType(ContentType.Application.Json)
		setBody(
			buildString {
				append("{\"body\":\"").append(body).append("\",\"lang\":\"en\"")
				parentId?.let { append(",\"parent_id\":\"").append(it).append('"') }
				append('}')
			},
		)
	}

	private val text = "This chapter was genuinely excellent"

	@Test
	fun `posting returns the created comment and reading it back finds it`() = testApplication {
		setup()
		val client = jsonClient()
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)

		val created = client.postComment(workId, secret("author"), text)
		assertEquals(HttpStatusCode.Created, created.status)
		val dto = created.body<CommentDto>()
		assertEquals(text, dto.body)
		assertTrue(dto.isMine)

		val page = client.get("/v1/works/$workId/comments") {
			header(HttpHeaders.Authorization, "Bearer ${secret("reader")}")
		}.body<CommentPageResponse>()
		assertEquals(1, page.total)
		assertEquals(dto.id, page.comments.single().id)
		// Someone else's comment: no vote of theirs on it, and not theirs to edit.
		assertEquals(0, page.comments.single().myVote)
		assertFalse(page.comments.single().isMine)
	}

	@Test
	fun `no credential means no comments, not even to read`() = testApplication {
		setup()
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)
		val response = client.get("/v1/works/$workId/comments")
		assertEquals(HttpStatusCode.Unauthorized, response.status)
		assertTrue(response.bodyAsText().contains("unauthorized"))
	}

	@Test
	fun `a short comment comes back as a code the app can translate`() = testApplication {
		setup()
		val client = jsonClient()
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)

		val response = client.postComment(workId, secret("author"), "first")
		assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
		val body = response.bodyAsText()
		assertTrue(body.contains("\"too_short\""), body)
		// The number comes from the rule rather than a literal: the app renders whatever the server
		// says the minimum is, so a change here must not need the test edited to match.
		assertTrue(body.contains("\"min\":${CommentRules.MIN_BODY_LENGTH}"), body)
	}

	@Test
	fun `a body the server cannot parse is the caller's mistake, and says so`() = testApplication {
		setup()
		val client = jsonClient()
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)

		val response = client.post("/v1/works/$workId/comments") {
			header(HttpHeaders.Authorization, "Bearer ${secret("author")}")
			contentType(ContentType.Application.Json)
			setBody("{\"not_a_field\":1}")
		}
		// Without the BadRequestException mapping this comes back as `internal_error`, which sends
		// whoever is writing a client looking in entirely the wrong place.
		assertEquals(HttpStatusCode.BadRequest, response.status)
		assertTrue(response.bodyAsText().contains("bad_request"), response.bodyAsText())
	}

	@Test
	fun `a filtered comment names the term and points at the rules`() = testApplication {
		setup()
		val client = jsonClient()
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)

		val response = client.postComment(workId, secret("author"), "this chapter was badword awful")
		assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
		val body = response.bodyAsText()
		assertTrue(body.contains("\"filter_blocked\""), body)
		assertTrue(body.contains("badword"), body)
		assertTrue(body.contains("https://example.invalid/rules"), body)
	}

	@Test
	fun `a fourth round in a two person chain is refused with its own code`() = testApplication {
		setup()
		val client = jsonClient()
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)
		val a = secret("a")
		val b = secret("b")

		var parent = client.postComment(workId, a, "$text 0").body<CommentDto>().id
		repeat(2) { round ->
			parent = client.postComment(workId, b, "$text b$round", parent).body<CommentDto>().id
			parent = client.postComment(workId, a, "$text a$round", parent).body<CommentDto>().id
		}
		parent = client.postComment(workId, b, "$text b-last", parent).body<CommentDto>().id

		val refused = client.postComment(workId, a, "$text a-too-many", parent)
		assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
		assertTrue(refused.bodyAsText().contains("chain_depth_exceeded"), refused.bodyAsText())
	}

	@Test
	fun `voting updates the counts in the response`() = testApplication {
		setup()
		val client = jsonClient()
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)
		val id = client.postComment(workId, secret("author"), text).body<CommentDto>().id

		val voted = client.put("/v1/comments/$id/vote") {
			header(HttpHeaders.Authorization, "Bearer ${secret("voter")}")
			contentType(ContentType.Application.Json)
			setBody("{\"value\":1}")
		}
		assertEquals(HttpStatusCode.OK, voted.status)
		val dto = voted.body<CommentDto>()
		assertEquals(1, dto.up)
		assertEquals(1, dto.myVote)
	}

	@Test
	fun `deleting your own comment empties it without removing it from the thread`() = testApplication {
		setup()
		val client = jsonClient()
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)
		val author = secret("author")
		val rootId = client.postComment(workId, author, text).body<CommentDto>().id
		client.postComment(workId, secret("other"), "$text reply", rootId)

		val deleted = client.delete("/v1/comments/$rootId") {
			header(HttpHeaders.Authorization, "Bearer $author")
		}
		assertEquals(HttpStatusCode.NoContent, deleted.status)

		val page = client.get("/v1/works/$workId/comments") {
			header(HttpHeaders.Authorization, "Bearer ${secret("reader")}")
		}.body<CommentPageResponse>()
		// The root is no longer listed, so its reply is not either - but the reply itself survives and
		// the count reflects only what is visible.
		assertEquals(1, page.total)
	}

	@Test
	fun `notifications hand back a cursor that suppresses the second delivery`() = testApplication {
		setup()
		val client = jsonClient()
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)
		val author = secret("author")
		val rootId = client.postComment(workId, author, text).body<CommentDto>().id
		client.postComment(workId, secret("replier"), "$text reply", rootId)

		val first = client.get("/v1/notifications") {
			header(HttpHeaders.Authorization, "Bearer $author")
		}.body<NotificationsResponse>()
		assertEquals(1, first.replies.size)
		assertEquals(rootId, first.replies.single().parentId)

		val second = client.get("/v1/notifications?since=${first.cursor}") {
			header(HttpHeaders.Authorization, "Bearer $author")
		}.body<NotificationsResponse>()
		assertTrue(second.replies.isEmpty())
	}
}
