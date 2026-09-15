package io.kotatsuredo.server

import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.comments.CommentService
import io.kotatsuredo.server.comments.PostResult
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.Identity
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.moderation.EnrolResult
import io.kotatsuredo.server.moderation.ModerationQueueRepository
import io.kotatsuredo.server.moderation.ModerationService
import io.kotatsuredo.server.moderation.OverviewRepository
import io.kotatsuredo.server.moderation.Moderator
import io.kotatsuredo.server.moderation.ModeratorRepository
import io.kotatsuredo.server.moderation.ModeratorRole
import io.kotatsuredo.server.moderation.Totp
import io.kotatsuredo.server.ratings.RatingRepository
import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.routes.MOD_SESSION_COOKIE
import io.kotatsuredo.server.routes.ADMIN_CSRF_HEADER
import io.kotatsuredo.server.routes.ADMIN_CSRF_VALUE
import io.kotatsuredo.server.works.WorkRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The panel API over real HTTP.
 *
 * [ModerationServiceTest] covers what the actions do; this covers who is allowed to call them, which
 * is the part where a mistake hands a moderator admin powers or lets an un-enrolled account act.
 */
class AdminRoutesTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val moderators by lazy { ModeratorRepository(source, testTotpCipher()) }
	private val queues by lazy { ModerationQueueRepository(source) }
	private val commentRepository by lazy { CommentRepository(source) }
	private val comments by lazy { CommentService(commentRepository) }
	private val identityRepository by lazy { IdentityRepository(PostgresTestBase.database.exposed, source) }
	private val works by lazy { WorkRepository(source) }
	private val moderation by lazy {
		ModerationService(
			moderators = moderators,
			queues = queues,
			comments = commentRepository,
			identities = identityRepository,
			works = works,
			ratings = RatingService(RatingRepository(source)),
		)
	}
	private val identities by lazy { IdentityService(identityRepository, DevicePepper.of("test")) }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE mod_action, mod_session, mod_totp_use, moderator, work_link_dispute, " +
						"comment, comment_vote, rating, work_rating_agg, rating_brigade_flag, work, " +
						"work_title, work_alias, work_cover_hash, work_external_id, work_relation, " +
						"work_merge_log, app_user, user_active_day, app_device, device_ban, " +
						"ban_evasion_flag CASCADE",
				)
			}
		}
	}

	private fun ApplicationTestBuilder.setup() {
		application {
			module(
				health = { true },
				identities = identities,
				comments = comments,
				works = works,
				moderation = moderation,
				queues = queues,
				overviews = OverviewRepository(PostgresTestBase.database.source),
				// A local HTTP run: a Secure cookie would never come back and nothing would work.
				secureCookies = false,
			)
		}
	}

	private fun enrol(moderator: Moderator): String {
		val login = assertIs<io.kotatsuredo.server.moderation.LoginResult.Ok>(
			moderation.login(moderator.username, "a-long-enough-password", null),
		)
		val secret = assertIs<EnrolResult.Started>(moderation.startEnrolment(moderator, login.token)).secret
		assertTrue(moderation.confirmEnrolment(moderator, login.token, Totp.code(secret, step())))
		return secret
	}

	private fun step() = System.currentTimeMillis() / 1000 / Totp.STEP_SECONDS

	private fun user(seed: String): Identity =
		(identities.hello(seed, DeviceIdentifiers("dev-$seed", null)) as HelloOutcome.Ok).identity

	private suspend fun HttpClient.login(username: String, password: String, code: String? = null): HttpResponse =
		post("/admin/api/login") {
			header(ADMIN_CSRF_HEADER, ADMIN_CSRF_VALUE)
			contentType(ContentType.Application.Json)
			setBody(
				buildString {
					append("{\"username\":\"").append(username).append("\",\"password\":\"").append(password)
					code?.let { append("\",\"code\":\"").append(it) }
					append("\"}")
				},
			)
		}

	/** Ktor's test client does not keep cookies, so the session rides explicitly. */
	private suspend fun HttpClient.withSession(
		token: String,
		path: String,
		body: String? = null,
	): HttpResponse = if (body == null) {
		get("/admin/api$path") { header(HttpHeaders.Cookie, "$MOD_SESSION_COOKIE=$token") }
	} else {
		post("/admin/api$path") {
			header(HttpHeaders.Cookie, "$MOD_SESSION_COOKIE=$token")
			header(ADMIN_CSRF_HEADER, ADMIN_CSRF_VALUE)
			contentType(ContentType.Application.Json)
			setBody(body)
		}
	}

	private fun HttpResponse.cookies(): List<Cookie> =
		headers.getAll(HttpHeaders.SetCookie).orEmpty().map(::parseServerSetCookieHeader)

	private fun HttpResponse.sessionCookie(): String =
		assertNotNull(cookies().firstOrNull { it.name == MOD_SESSION_COOKIE }, "no session cookie").value

	// -- login -------------------------------------------------------------------------------------

	@Test
	fun `admin mutations require the anti csrf header`() = testApplication {
		setup()
		val response = client.post("/admin/api/login") {
			contentType(ContentType.Application.Json)
			setBody("""{"username":"root","password":"not-the-password"}""")
		}
		assertEquals(HttpStatusCode.Forbidden, response.status)
	}

	@Test
	fun `signing in returns a session in an httpOnly cookie`() = testApplication {
		setup()
		val root = assertNotNull(moderation.bootstrapFirstAdmin("root", "a-long-enough-password"))
		enrol(root)

		val secret = assertNotNull(moderators.totpSecretOf(root.id))
		val response = client.login("root", "a-long-enough-password", Totp.code(secret, step() + 1))
		assertEquals(HttpStatusCode.OK, response.status)

		val cookie = assertNotNull(response.cookies().firstOrNull { it.name == MOD_SESSION_COOKIE })
		// The panel's own script must not be able to read this: an XSS on the panel would otherwise
		// hand over a moderator session.
		assertTrue(cookie.httpOnly, "the session cookie is readable from JavaScript")
		assertEquals("Strict", cookie.extensions["SameSite"], "SameSite=Strict is the CSRF defence")
		assertTrue(response.bodyAsText().contains("\"role\":\"admin\""))
	}

	@Test
	fun `a wrong password says nothing about whether the account exists`() = testApplication {
		setup()
		moderation.bootstrapFirstAdmin("root", "a-long-enough-password")

		val wrong = client.login("root", "not-the-password")
		val unknown = client.login("nobody-at-all", "not-the-password")
		assertEquals(HttpStatusCode.Unauthorized, wrong.status)
		assertEquals(wrong.bodyAsText(), unknown.bodyAsText())
	}

	@Test
	fun `no cookie means no panel`() = testApplication {
		setup()
		assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/me").status)
		assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/queues/recent").status)
	}

	// -- the enrolment gate ------------------------------------------------------------------------

	@Test
	fun `an account that has not enrolled can do nothing but enrol`() = testApplication {
		setup()
		moderation.bootstrapFirstAdmin("root", "a-long-enough-password")
		val token = client.login("root", "a-long-enough-password").sessionCookie()

		// The session is real...
		assertEquals(HttpStatusCode.OK, client.withSession(token, "/me").status)
		val enrol = client.withSession(token, "/totp/enroll", "{}")
		assertEquals(HttpStatusCode.OK, enrol.status)

		// ...and worth nothing until enrolment finishes.
		val blocked = client.withSession(token, "/queues/recent")
		assertEquals(HttpStatusCode.Forbidden, blocked.status)
		assertTrue(blocked.bodyAsText().contains("totp_enrolment_required"), blocked.bodyAsText())
	}

	// -- roles -------------------------------------------------------------------------------------

	@Test
	fun `a moderator is refused every admin-only endpoint`() = testApplication {
		setup()
		val root = assertNotNull(moderation.bootstrapFirstAdmin("root", "a-long-enough-password"))
		enrol(root)
		val helper = assertNotNull(
			moderation.invite(root, "helper", "a-long-enough-password", ModeratorRole.MODERATOR),
		)
		val secret = enrol(helper)
		val token = client.login("helper", "a-long-enough-password", Totp.code(secret, step() + 1)).sessionCookie()

		// The day-to-day queues are theirs.
		assertEquals(HttpStatusCode.OK, client.withSession(token, "/queues/disliked").status)

		listOf("/moderators", "/devices").forEach { path ->
			val response = client.withSession(token, path)
			assertEquals(HttpStatusCode.Forbidden, response.status, "GET $path")
			assertTrue(response.bodyAsText().contains("admin_required"), response.bodyAsText())
		}
		val merge = client.withSession(token, "/works/merge", "{\"from\":\"1\",\"into\":\"2\",\"reason\":\"no\"}")
		assertEquals(HttpStatusCode.Forbidden, merge.status)
	}

	// -- actions -----------------------------------------------------------------------------------

	@Test
	fun `removing a comment needs a reason, and the reason reaches the audit log`() = testApplication {
		setup()
		val root = assertNotNull(moderation.bootstrapFirstAdmin("root", "a-long-enough-password"))
		val secret = enrol(root)
		val token = client.login("root", "a-long-enough-password", Totp.code(secret, step() + 1)).sessionCookie()

		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)
		val posted = assertIs<PostResult.Posted>(
			comments.post(workId, null, user("a"), null, "A comment somebody objected to.", false, "en"),
		)
		val id = posted.view.comment.id

		val noReason = client.withSession(token, "/comments/$id/remove", "{}")
		assertEquals(HttpStatusCode.BadRequest, noReason.status)

		val removed = client.withSession(token, "/comments/$id/remove", "{\"reason\":\"off topic\"}")
		assertEquals(HttpStatusCode.OK, removed.status)

		val log = client.withSession(token, "/actions").bodyAsText()
		assertTrue(log.contains("remove_comment"), log)
		assertTrue(log.contains("off topic"), log)
	}

	@Test
	fun `signing out invalidates the session`() = testApplication {
		setup()
		val root = assertNotNull(moderation.bootstrapFirstAdmin("root", "a-long-enough-password"))
		val secret = enrol(root)
		val token = client.login("root", "a-long-enough-password", Totp.code(secret, step() + 1)).sessionCookie()

		assertEquals(HttpStatusCode.OK, client.withSession(token, "/me").status)
		assertEquals(HttpStatusCode.OK, client.withSession(token, "/logout", "{}").status)
		assertEquals(HttpStatusCode.Unauthorized, client.withSession(token, "/me").status)
	}

	// -- the panel itself --------------------------------------------------------------------------

	@Test
	fun `the overview answers with every section the home page draws`() = testApplication {
		setup()
		val root = assertNotNull(moderation.bootstrapFirstAdmin("root", "a-long-enough-password"))
		val secret = enrol(root)
		// The next step, because confirming the enrolment just burned this one.
		val token = client.login("root", "a-long-enough-password", Totp.code(secret, step() + 1))
			.sessionCookie()

		val body = client.withSession(token, "/overview")
		assertEquals(HttpStatusCode.OK, body.status)
		val text = body.bodyAsText()

		// Named rather than shape-checked: the home page reads each of these, and a renamed field
		// would leave that part of the dashboard silently blank rather than failing.
		for (field in listOf("totals", "today", "queues", "series", "top_rules", "languages")) {
			assertTrue(text.contains("\"$field\""), "the overview is missing `$field`: $text")
		}
		// Fourteen days, zero-filled, so the chart never has to guess at a gap.
		assertEquals(14, Regex("\"day\":").findAll(text).count())
	}

	@Test
	fun `the panel is served from the same container`() = testApplication {
		setup()
		val response = client.get("/admin/")
		assertEquals(HttpStatusCode.OK, response.status)
		val body = response.bodyAsText()
		assertTrue(body.contains("Kotatsu-Redo moderation"), "the panel did not render")
		// Nothing external: the panel has to keep working when whatever CDN it used stops existing.
		assertTrue(!body.contains("https://cdn"), "the panel pulls in a third-party script")
		assertEquals(HttpStatusCode.OK, client.get("/admin/panel.js").status)
		assertEquals(HttpStatusCode.OK, client.get("/admin/panel.css").status)
	}

	@Test
	fun `the panel is hardened against script injection and framing`() = testApplication {
		setup()
		val response = client.get("/admin/")
		val csp = assertNotNull(response.headers["Content-Security-Policy"], "no CSP on the panel")

		// No inline script may run: an injection that gets past the escaping still does nothing.
		assertTrue(csp.contains("script-src 'self'"), csp)
		assertTrue(!csp.contains("script-src 'self' 'unsafe-inline'"), "inline scripts are allowed: $csp")
		// A framed panel is a moderator tricked into clicking "ban".
		assertTrue(csp.contains("frame-ancestors 'none'"), csp)
		assertEquals("DENY", response.headers["X-Frame-Options"])
		assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
	}

	@Test
	fun `the panel headers do not leak onto the app's own api`() = testApplication {
		setup()
		// The CSP is for a browser page; putting it on every response would be cargo cult.
		assertNull(client.get("/v1/health").headers["Content-Security-Policy"])
	}
}
