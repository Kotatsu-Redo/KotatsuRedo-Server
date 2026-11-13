package io.kotatsuredo.server

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.comments.CommentService
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.ratings.RatingRepository
import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.routes.ExportDto
import io.kotatsuredo.server.works.WorkLinker
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkResolver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Not interested": `POST /v1/works/pass`.
 *
 * The contract the app is written against, and the one promise that matters most: saying no to a
 * suggestion is not opening it, so it must never create a work, an alias or a note of what an
 * account opened.
 */
class PassRoutesTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val works by lazy { WorkRepository(source) }
	private val identities by lazy {
		IdentityService(IdentityRepository(PostgresTestBase.database.exposed, source), DevicePepper.of("test"))
	}
	private val commentRepository by lazy { CommentRepository(source) }
	private val json = Json { ignoreUnknownKeys = true }
	private val limiter = RateLimiter()

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE work, work_title, work_alias, work_alias_observation, work_genre_observation, " +
						"work_genre, work_external_id, work_merge_log, work_pass, rating, work_rating_agg, " +
						"comment, comment_vote, app_user, user_active_day, app_device CASCADE",
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
				ratings = RatingService(RatingRepository(source)),
				comments = CommentService(commentRepository),
				commentsRepository = commentRepository,
				limiter = limiter,
			)
		}
	}

	private fun secret(seed: String) = seed.padEnd(32, '_')

	private fun user(seed: String): String =
		(identities.hello(secret(seed), DeviceIdentifiers("dev-$seed", null)) as HelloOutcome.Ok).identity.id

	private suspend fun ApplicationTestBuilder.pass(seed: String?, body: String): HttpResponse =
		client.post("/v1/works/pass") {
			if (seed != null) header(HttpHeaders.Authorization, "Bearer ${secret(seed)}")
			contentType(ContentType.Application.Json)
			setBody(body)
		}

	private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

	private fun count(sql: String): Int = source.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery(sql).use { rows -> rows.next(); rows.getInt(1) }
		}
	}

	private fun passes(userId: String) = count("SELECT count(*) FROM work_pass WHERE user_id = '$userId'")

	private fun alias(workId: Long, key: String, userId: String) {
		works.linkAlias("SOURCE_A", key, workId, 1.0, "test")
		works.observeAlias("SOURCE_A", key, userId, workId, listOf("key"))
	}

	@Test
	fun `passing by work id records it and answers recorded true`() = testApplication {
		setup()
		val me = user("me")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)

		val response = pass("me", """{"work_id":"$id","passed":true}""")

		assertEquals(HttpStatusCode.OK, response.status)
		val body = response.obj()
		assertEquals(id.toString(), body.getValue("work_id").jsonPrimitive.content)
		assertEquals("true", body.getValue("recorded").jsonPrimitive.content)
		assertEquals(1, passes(me))
	}

	@Test
	fun `passing by source and key finds the work through its alias`() = testApplication {
		setup()
		val me = user("me")
		val other = user("other")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		alias(id, "/manga/tower", other)

		val response = pass("me", """{"source":"SOURCE_A","key":"/manga/tower","passed":true}""")

		assertEquals(HttpStatusCode.OK, response.status)
		assertEquals(id.toString(), response.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals(1, passes(me))
		// Passing is not opening: the caller has no note of the entry.
		assertEquals(0, count("SELECT count(*) FROM work_alias_observation WHERE user_id = '$me'"))
	}

	@Test
	fun `an unknown work answers null and recorded false and creates nothing`() = testApplication {
		setup()
		val me = user("me")
		val other = user("other")
		val known = works.createWork("Known", 2020, "manga", false)
		alias(known, "/manga/known", other)
		val before = listOf("work", "work_alias", "work_alias_observation", "work_genre_observation", "work_cover_observation")
			.map { it to count("SELECT count(*) FROM $it") }

		val byEntry = pass("me", """{"source":"SOURCE_A","key":"/manga/never-seen","passed":true}""")
		val byId = pass("me", """{"work_id":"999999","passed":true}""")
		val both = pass("me", """{"work_id":"999999","source":"SOURCE_A","key":"/manga/never-seen","passed":true}""")

		listOf(byEntry, byId, both).forEach { response ->
			assertEquals(HttpStatusCode.OK, response.status)
			assertEquals(
				"""{"work_id":null,"recorded":false}""",
				response.bodyAsText(),
			)
		}
		assertEquals(before, before.map { it.first to count("SELECT count(*) FROM ${it.first}") })
		assertEquals(0, passes(me))
	}

	@Test
	fun `an unknown work id falls back to the alias`() = testApplication {
		setup()
		val me = user("me")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		alias(id, "/manga/tower", user("other"))

		val response = pass("me", """{"work_id":"999999","source":"SOURCE_A","key":"/manga/tower","passed":true}""")

		assertEquals(id.toString(), response.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals(1, passes(me))
	}

	@Test
	fun `undoing removes the pass and answers recorded false with the work id`() = testApplication {
		setup()
		val me = user("me")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		pass("me", """{"work_id":"$id","passed":true}""")

		val undone = pass("me", """{"work_id":"$id","passed":false}""")

		assertEquals(HttpStatusCode.OK, undone.status)
		assertEquals("""{"work_id":"$id","recorded":false}""", undone.bodyAsText())
		assertEquals(0, passes(me))
		// Undoing what was never passed is fine too.
		assertEquals(HttpStatusCode.OK, pass("me", """{"work_id":"$id","passed":false}""").status)
	}

	@Test
	fun `repeating a pass is idempotent`() = testApplication {
		setup()
		val me = user("me")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)

		val first = pass("me", """{"work_id":"$id","passed":true}""")
		val firstDate = source.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("SELECT created_at FROM work_pass").use { rows -> rows.next(); rows.getTimestamp(1) }
			}
		}
		val second = pass("me", """{"work_id":"$id","passed":true}""")

		assertEquals(first.bodyAsText(), second.bodyAsText())
		assertEquals(1, passes(me))
		val secondDate = source.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery("SELECT created_at FROM work_pass").use { rows -> rows.next(); rows.getTimestamp(1) }
			}
		}
		assertEquals(firstDate, secondDate, "the first date stands")
	}

	@Test
	fun `a merged work id resolves to the survivor`() = testApplication {
		setup()
		val me = user("me")
		val survivor = works.createWork("Tower of Mirrors", 2020, "manga", false)
		val duplicate = works.createWork("Tower of Mirrors (dup)", 2020, "manga", false)
		alias(duplicate, "/manga/dup", user("other"))
		works.mergeWorks(from = duplicate, into = survivor, reason = "test")

		val byId = pass("me", """{"work_id":"$duplicate","passed":true}""")
		val byAlias = pass("me", """{"source":"SOURCE_A","key":"/manga/dup","passed":true}""")

		assertEquals(survivor.toString(), byId.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals(survivor.toString(), byAlias.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals(1, passes(me))
		assertEquals(1, count("SELECT count(*) FROM work_pass WHERE work_id = $survivor"))
	}

	@Test
	fun `malformed requests are 400 with the usual error shape`() = testApplication {
		setup()
		user("me")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		val bad = listOf(
			"""{"passed":true}""", // names nothing
			"""{"work_id":"$id"}""", // no passed
			"""{"work_id":"$id","passed":"yes"}""",
			"""{"work_id":"not-a-number","passed":true}""",
			"""{"source":"SOURCE_A","passed":true}""", // half a pair
			"""{"key":"/manga/x","passed":true}""",
			"""{"source":"SOURCE A!","key":"/manga/x","passed":true}""",
			"""{"source":"SOURCE_A","key":"","passed":true}""",
			"""not json""",
			"""[]""",
		)
		bad.forEach { body ->
			val response = pass("me", body)
			assertEquals(HttpStatusCode.BadRequest, response.status, body)
			assertEquals("bad_request", response.obj().getValue("error").jsonPrimitive.content, body)
		}
		assertEquals(0, count("SELECT count(*) FROM work_pass"))
	}

	@Test
	fun `no credentials or unknown credentials are refused`() = testApplication {
		setup()
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		val body = """{"work_id":"$id","passed":true}"""

		assertEquals(HttpStatusCode.Unauthorized, pass(null, body).status)
		assertEquals(HttpStatusCode.Unauthorized, pass("nobody-has-this-key", body).status)
		assertEquals(0, count("SELECT count(*) FROM work_pass"))
	}

	@Test
	fun `more than 300 passes in a day by one account is 429`() = testApplication {
		setup()
		val me = user("me")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		val body = """{"work_id":"$id","passed":true}"""
		val bucket = RateLimiter.Bucket.PASSES
		// 299 already spent, so exactly one more is allowed.
		repeat(299) { limiter.check(bucket, me, TrustTier.NEW) }

		assertEquals(HttpStatusCode.OK, pass("me", body).status)
		val refused = pass("me", """{"work_id":"$id","passed":false}""")

		assertEquals(HttpStatusCode.TooManyRequests, refused.status)
		assertEquals("rate_limited", refused.obj().getValue("error").jsonPrimitive.content)
		assertEquals("passes", refused.obj().getValue("limit").jsonPrimitive.content)
		assertEquals(1, passes(me), "the refused undo changed nothing")
		// Another account is unaffected.
		user("someone-else")
		assertEquals(HttpStatusCode.OK, pass("someone-else", body).status)
	}

	@Test
	fun `a malformed request does not spend the quota`() = testApplication {
		setup()
		val me = user("me")
		val bucket = RateLimiter.Bucket.PASSES
		repeat(299) { limiter.check(bucket, me, TrustTier.NEW) }

		assertEquals(HttpStatusCode.BadRequest, pass("me", """{"passed":true}""").status)
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)

		assertEquals(HttpStatusCode.OK, pass("me", """{"work_id":"$id","passed":true}""").status)
	}

	// -- export, deletion, merge ----------------------------------------------------------------

	@Test
	fun `the export lists the account's own passes and nobody else's`() = testApplication {
		setup()
		val me = user("me")
		val other = user("other")
		val first = works.createWork("First", 2020, "manga", false)
		val second = works.createWork("Second", 2020, "manga", false)
		works.setPass(first, me)
		works.setPass(second, me)
		works.setPass(second, other)

		val response = client.get("/v1/identity/export") {
			header(HttpHeaders.Authorization, "Bearer ${secret("me")}")
		}

		assertEquals(HttpStatusCode.OK, response.status)
		val export = json.decodeFromString<ExportDto>(response.bodyAsText())
		assertEquals(listOf(first.toString(), second.toString()), export.passes.map { it.workId })
		assertTrue(export.passes.all { it.passedAt.endsWith("Z") })
	}

	@Test
	fun `deleting the account deletes its passes and only its passes`() {
		val leaving = user("leaving")
		val staying = user("staying")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		works.setPass(id, leaving)
		works.setPass(id, staying)

		identities.deleteEverything(leaving)

		assertEquals(0, passes(leaving))
		assertEquals(1, passes(staying))
	}

	/**
	 * Like a rating, a pass by someone who passed on both works stays behind on the loser (unreachable
	 * while the merge stands) rather than being dropped, so no fact is lost and an unmerge restores it.
	 */
	@Test
	fun `a merge moves passes to the survivor and keeps a collided one behind`() {
		val both = user("both")
		val onlyDuplicate = user("only-duplicate")
		val onlySurvivor = user("only-survivor")
		val survivor = works.createWork("Survivor", 2020, "manga", false)
		val duplicate = works.createWork("Duplicate", 2020, "manga", false)
		works.setPass(survivor, both)
		works.setPass(duplicate, both)
		works.setPass(duplicate, onlyDuplicate)
		works.setPass(survivor, onlySurvivor)

		works.mergeWorks(from = duplicate, into = survivor, reason = "test")

		assertEquals(3, count("SELECT count(*) FROM work_pass WHERE work_id = $survivor"))
		assertEquals(1, count("SELECT count(*) FROM work_pass WHERE work_id = $survivor AND user_id = '$both'"))
		assertEquals(1, count("SELECT count(*) FROM work_pass WHERE work_id = $survivor AND user_id = '$onlyDuplicate'"))
		assertEquals(listOf(both), passers(duplicate), "the collided one waits on the loser")
	}

	private fun passers(workId: Long): List<String> = source.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery("SELECT user_id FROM work_pass WHERE work_id = $workId ORDER BY user_id").use { rows ->
				buildList { while (rows.next()) add(rows.getString(1)) }
			}
		}
	}

	@Test
	fun `merge then unmerge puts each pass back on the work it was made on`() = testApplication {
		setup()
		val both = user("both")
		val onlyDuplicate = user("only-duplicate")
		val onlySurvivor = user("only-survivor")
		val survivor = works.createWork("Survivor", 2020, "manga", false)
		val duplicate = works.createWork("Duplicate", 2020, "manga", false)
		works.setPass(survivor, both)
		works.setPass(duplicate, both)
		works.setPass(duplicate, onlyDuplicate)
		works.setPass(survivor, onlySurvivor)

		works.mergeWorks(from = duplicate, into = survivor, reason = "test")
		// Made while merged, through the old id: lands on the survivor and stays there.
		pass("only-survivor", """{"work_id":"$duplicate","passed":true}""")
		works.unmergeWork(duplicate)

		assertEquals(listOf(both, onlyDuplicate).sorted(), passers(duplicate))
		assertEquals(listOf(both, onlySurvivor).sorted(), passers(survivor))
		val export = json.decodeFromString<ExportDto>(
			client.get("/v1/identity/export") {
				header(HttpHeaders.Authorization, "Bearer ${secret("only-duplicate")}")
			}.bodyAsText(),
		)
		assertEquals(listOf(duplicate.toString()), export.passes.map { it.workId })

		// An undo for the original work, after the unmerge, removes it.
		val undone = pass("only-duplicate", """{"work_id":"$duplicate","passed":false}""")
		assertEquals("""{"work_id":"$duplicate","recorded":false}""", undone.bodyAsText())
		assertEquals(0, passes(onlyDuplicate))
		assertEquals(listOf(both), passers(duplicate))
	}

	@Test
	fun `a banned caller is refused`() = testApplication {
		setup()
		val me = user("me")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		source.connection.use { it.createStatement().use { s -> s.execute("UPDATE app_user SET is_banned = TRUE WHERE id = '$me'") } }

		val response = pass("me", """{"work_id":"$id","passed":true}""")

		assertEquals(HttpStatusCode.Forbidden, response.status)
		assertEquals("banned", response.obj().getValue("error").jsonPrimitive.content)
		assertEquals(0, passes(me))
	}

	@Test
	fun `the export pages through more passes than fit on one page`() = testApplication {
		setup()
		val me = user("me")
		val ids = List(520) { works.createWork("Work $it", 2020, "manga", false) }
		ids.forEach { works.setPass(it, me) }

		val export = json.decodeFromString<ExportDto>(
			client.get("/v1/identity/export") {
				header(HttpHeaders.Authorization, "Bearer ${secret("me")}")
			}.bodyAsText(),
		)

		assertEquals(ids.map { it.toString() }, export.passes.map { it.workId })
	}

	@Test
	fun `a new account can pass, export and undo`() = testApplication {
		setup()
		val me = user("me") // brand new: tier 0
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)

		assertEquals(true, pass("me", """{"work_id":"$id","passed":true}""").obj().getValue("recorded").jsonPrimitive.content.toBoolean())
		val export = json.decodeFromString<ExportDto>(
			client.get("/v1/identity/export") {
				header(HttpHeaders.Authorization, "Bearer ${secret("me")}")
			}.bodyAsText(),
		)
		assertEquals(listOf(id.toString()), export.passes.map { it.workId })
		pass("me", """{"work_id":"$id","passed":false}""")
		assertEquals(0, passes(me))
	}

	@Test
	fun `the response keeps work_id as an explicit null`() = testApplication {
		setup()
		user("me")
		val body = pass("me", """{"source":"SOURCE_A","key":"/manga/never","passed":false}""").obj()
		assertEquals(JsonNull, body.getValue("work_id"))
	}

	// -- finding a work by source entry ----------------------------------------------------------

	private fun observe(workId: Long, key: String, vararg users: String) {
		users.forEach { works.observeAlias("SOURCE_A", key, it, workId, listOf("key")) }
	}

	/** Past the lowest trust tier: more than a day old and active on three days. */
	private fun establish(vararg users: String) {
		source.connection.use { connection ->
			users.forEach { user ->
				connection.prepareStatement("UPDATE app_user SET created_at = now() - interval '10 days' WHERE id = ?").use {
					it.setString(1, user)
					it.executeUpdate()
				}
				(1..3).forEach { daysAgo ->
					connection.prepareStatement(
						"INSERT INTO user_active_day (user_id, day) VALUES (?, current_date - ?) ON CONFLICT DO NOTHING",
					).use {
						it.setString(1, user)
						it.setInt(2, daysAgo)
						it.executeUpdate()
					}
				}
			}
		}
	}

	private suspend fun ApplicationTestBuilder.passByEntry(seed: String, key: String) = pass(seed, """{"source":"SOURCE_A","key":"$key","passed":true}""")

	@Test
	fun `an entry the caller itself observed is found by source and key`() = testApplication {
		setup()
		val me = user("me")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		observe(id, "/manga/mine", me)

		val response = passByEntry("me", "/manga/mine")

		assertEquals(id.toString(), response.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals("true", response.obj().getValue("recorded").jsonPrimitive.content)
		assertEquals(1, passes(me))
		assertEquals(1, count("SELECT count(*) FROM work_alias_observation WHERE user_id = '$me'"), "no new note of the entry")
	}

	@Test
	fun `an entry two other accounts observed is found, one is not enough`() = testApplication {
		setup()
		val me = user("me")
		val first = user("first")
		val second = user("second")
		establish(first, second)
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		observe(id, "/manga/two", first)

		val one = passByEntry("me", "/manga/two")
		assertEquals(JsonNull, one.obj().getValue("work_id"))
		assertEquals(0, passes(me))

		observe(id, "/manga/two", second)
		val two = passByEntry("me", "/manga/two")
		assertEquals(id.toString(), two.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals(1, passes(me))
		assertEquals(0, count("SELECT count(*) FROM work_alias_observation WHERE user_id = '$me'"), "passing is not opening")
	}

	@Test
	fun `observations by sanctioned accounts do not corroborate an entry`() = testApplication {
		setup()
		val me = user("me")
		val banned = user("banned")
		val shadowbanned = user("shadowbanned")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		establish(banned, shadowbanned)
		observe(id, "/manga/sanctioned", banned, shadowbanned)
		source.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.execute("UPDATE app_user SET is_banned = TRUE WHERE id = '$banned'")
				statement.execute("UPDATE app_user SET is_shadowbanned = TRUE WHERE id = '$shadowbanned'")
			}
		}

		val response = passByEntry("me", "/manga/sanctioned")

		assertEquals(JsonNull, response.obj().getValue("work_id"))
		assertEquals(0, passes(me))
	}

	@Test
	fun `an alias nobody verified is not trusted on its own`() = testApplication {
		setup()
		val me = user("me")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		source.connection.use { connection ->
			connection.prepareStatement(
				"INSERT INTO work_alias (source, source_key, work_id, is_verified) VALUES ('SOURCE_A', '/manga/legacy', ?, FALSE)",
			).use { statement ->
				statement.setLong(1, id)
				statement.executeUpdate()
			}
		}

		val response = passByEntry("me", "/manga/legacy")

		assertEquals(JsonNull, response.obj().getValue("work_id"))
		assertEquals("false", response.obj().getValue("recorded").jsonPrimitive.content)
		assertEquals(0, passes(me))
	}

	@Test
	fun `what the caller observed wins over what other accounts agree on`() = testApplication {
		setup()
		val me = user("me")
		val first = user("first")
		val second = user("second")
		establish(first, second)
		val mine = works.createWork("Mine", 2020, "manga", false)
		val theirs = works.createWork("Theirs", 2020, "manga", false)
		observe(mine, "/manga/disputed", me)
		observe(theirs, "/manga/disputed", first, second)

		val response = passByEntry("me", "/manga/disputed")

		assertEquals(mine.toString(), response.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals(1, count("SELECT count(*) FROM work_pass WHERE work_id = $mine AND user_id = '$me'"))
		assertEquals(0, count("SELECT count(*) FROM work_pass WHERE work_id = $theirs"))
	}

	@Test
	fun `a verified alias wins over what the caller itself observed`() = testApplication {
		setup()
		val me = user("me")
		val mine = works.createWork("Mine", 2020, "manga", false)
		val verified = works.createWork("Verified", 2020, "manga", false)
		observe(mine, "/manga/contested", me)
		works.linkAlias("SOURCE_A", "/manga/contested", verified, 1.0, "test")

		val response = passByEntry("me", "/manga/contested")

		assertEquals(verified.toString(), response.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals(1, count("SELECT count(*) FROM work_pass WHERE work_id = $verified AND user_id = '$me'"))
		assertEquals(0, count("SELECT count(*) FROM work_pass WHERE work_id = $mine"))
	}

	/** Two accounts made today must not be able to decide where somebody else's pass is recorded. */
	@Test
	fun `fresh accounts do not corroborate a pass but established ones do, and the public route is unchanged`() = testApplication {
		setup()
		val me = user("me")
		val first = user("first")
		val second = user("second")
		val id = works.createWork("Tower of Mirrors", 2020, "manga", false)
		observe(id, "/manga/fresh", first, second)
		val publicBefore = client.get("/v1/public/similar?source=SOURCE_A&key=/manga/fresh")

		val fresh = passByEntry("me", "/manga/fresh")
		assertEquals(JsonNull, fresh.obj().getValue("work_id"))
		assertEquals(0, passes(me))

		establish(first, second)
		val established = passByEntry("me", "/manga/fresh")
		assertEquals(id.toString(), established.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals(1, passes(me))

		// The similar-works route keeps its own, older rule: two accounts of any age are enough for it.
		val publicAfter = client.get("/v1/public/similar?source=SOURCE_A&key=/manga/fresh")
		assertEquals(HttpStatusCode.OK, publicBefore.status)
		assertEquals(publicBefore.status, publicAfter.status)
		assertEquals(publicBefore.bodyAsText(), publicAfter.bodyAsText())
	}

	/**
	 * A pass walking a merge holds the merged work and then waits for its own pass row, which an unmerge
	 * moves back. The unmerge must therefore wait for the work first and move nothing until it has it. The
	 * merged work has the higher id here, the order a lock taken lowest id first would get wrong.
	 */
	@Test
	fun `a pass in flight on the merged work holds an unmerge back without moving anything`() {
		val me = user("me")
		val survivor = works.createWork("Survivor", 2020, "manga", false)
		val duplicate = works.createWork("Duplicate", 2020, "manga", false)
		works.setPass(duplicate, me)
		works.mergeWorks(from = duplicate, into = survivor, reason = "test")

		source.connection.use { connection ->
			connection.autoCommit = false
			connection.createStatement().use { it.execute("SELECT 1 FROM work WHERE id = $duplicate FOR SHARE") }
			val unmerge = java.util.concurrent.CompletableFuture.supplyAsync { works.unmergeWork(duplicate) }

			Thread.sleep(700)
			assertTrue(!unmerge.isDone, "the unmerge must wait for the pass")
			assertEquals(1, count("SELECT count(*) FROM work_pass WHERE work_id = $survivor"), "and move nothing meanwhile")
			assertEquals(0, count("SELECT count(*) FROM work_pass WHERE work_id = $duplicate"))

			connection.commit()
			assertEquals(duplicate to survivor, unmerge.get(10, java.util.concurrent.TimeUnit.SECONDS))
		}
		assertEquals(1, count("SELECT count(*) FROM work_pass WHERE work_id = $duplicate"))
		assertEquals(0, count("SELECT count(*) FROM work_pass WHERE work_id = $survivor"))
	}

	@Test
	fun `a pass by source and key follows a merge to the survivor`() = testApplication {
		setup()
		val me = user("me")
		val other = user("other")
		val first = user("first")
		val second = user("second")
		val survivor = works.createWork("Tower of Mirrors", 2020, "manga", false)
		establish(first, second)
		val duplicate = works.createWork("Tower of Mirrors (dup)", 2020, "manga", false)
		observe(duplicate, "/manga/own-dup", me)
		observe(duplicate, "/manga/shared-dup", first, second)
		works.mergeWorks(from = duplicate, into = survivor, reason = "test")

		val own = passByEntry("me", "/manga/own-dup")
		val shared = passByEntry("other", "/manga/shared-dup")

		assertEquals(survivor.toString(), own.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals(survivor.toString(), shared.obj().getValue("work_id").jsonPrimitive.content)
		assertEquals(1, count("SELECT count(*) FROM work_pass WHERE work_id = $survivor AND user_id = '$me'"))
		assertEquals(1, count("SELECT count(*) FROM work_pass WHERE work_id = $survivor AND user_id = '$other'"))
		assertEquals(0, count("SELECT count(*) FROM work_pass WHERE work_id = $duplicate"))
	}
}
