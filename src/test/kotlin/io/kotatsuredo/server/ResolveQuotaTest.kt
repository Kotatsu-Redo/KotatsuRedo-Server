package io.kotatsuredo.server

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.routes.ResolveResponse
import io.kotatsuredo.server.works.WorkLinker
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkResolver
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The work-mutation quota on `/v1/works/resolve` pays for what resolving writes, not for asking.
 *
 * 9.8.5 resolves the same manga from the rating row and the related row of one details screen, and
 * again on each refresh of its data before the first answer is cached. Charged per request, that was
 * two to four units a manga: a reader browsing ran out within the hour and lost ratings and comments
 * on everything new. These pin the rule that fixed it - a repeat is free, a concurrent duplicate is
 * charged once - and that a reader who has truly run out still writes nothing.
 */
class ResolveQuotaTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val works by lazy { WorkRepository(source) }
	private val identities by lazy {
		IdentityService(IdentityRepository(PostgresTestBase.database.exposed, source), DevicePepper.of("test"))
	}
	private val json = Json { ignoreUnknownKeys = true }
	private val limiter = RateLimiter()

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
				limiter = limiter,
			)
		}
	}

	private fun secret(seed: String) = seed.padEnd(32, '_')

	private fun user(seed: String): String =
		(identities.hello(secret(seed), DeviceIdentifiers("dev-$seed", null)) as HelloOutcome.Ok).identity.id

	/**
	 * Spends the account's work-mutation quota down to exactly [left] units, whatever earlier requests
	 * already took. Spending a fixed amount is silently refused once anything has been charged.
	 */
	private fun spendQuota(userId: String, left: Int) {
		val bucket = RateLimiter.Bucket.WORK_MUTATION
		while (limiter.check(bucket, userId, TrustTier.NEW, commit = false, cost = left + 1) == RateLimiter.Decision.Allowed) {
			limiter.check(bucket, userId, TrustTier.NEW)
		}
	}

	private suspend fun ApplicationTestBuilder.resolve(seed: String, key: String, title: String): HttpResponse =
		client.post("/v1/works/resolve") {
			header(HttpHeaders.Authorization, "Bearer ${secret(seed)}")
			contentType(ContentType.Application.Json)
			setBody("""{"works":[{"source":"SOURCE_A","key":"$key","title":"$title"}]}""")
		}

	private suspend fun HttpResponse.workId(): String =
		json.decodeFromString<ResolveResponse>(bodyAsText()).resolved.single().workId

	private fun count(sql: String): Int = source.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery(sql).use { rows -> rows.next(); rows.getInt(1) }
		}
	}

	@Test
	fun `a manga already resolved is answered after the quota is spent`() = testApplication {
		setup()
		val reader = user("reader")
		val first = resolve("reader", "/manga/tower", "Tower of Mirrors")
		assertEquals(HttpStatusCode.OK, first.status)

		spendQuota(reader, left = 0)
		val again = resolve("reader", "/manga/tower", "Tower of Mirrors")

		assertEquals(HttpStatusCode.OK, again.status, "opening it again must not need the quota")
		assertEquals(first.workId(), again.workId())
	}

	/** What one 9.8.5 details screen sends for a manga it has not cached yet. */
	@Test
	fun `concurrent duplicates of a new manga are charged once`() = testApplication {
		setup()
		val reader = user("reader")
		spendQuota(reader, left = 1)

		val responses = coroutineScope {
			List(4) { async { resolve("reader", "/manga/new", "A Manga Nobody Had") } }.awaitAll()
		}

		assertEquals(List(4) { HttpStatusCode.OK }, responses.map { it.status })
		assertEquals(1, responses.map { it.workId() }.distinct().size)
		assertEquals(1, count("SELECT count(*) FROM work"))
	}

	@Test
	fun `a new manga past the quota is refused and writes nothing`() = testApplication {
		setup()
		val reader = user("reader")
		spendQuota(reader, left = 0)

		val refused = resolve("reader", "/manga/new", "A Manga Nobody Had")

		assertEquals(HttpStatusCode.TooManyRequests, refused.status)
		assertEquals(0, count("SELECT count(*) FROM work"))
		assertEquals(0, count("SELECT count(*) FROM work_alias_observation"))
	}

	/** The repeat is free for the account that resolved it, not for everybody who asks. */
	@Test
	fun `another account still pays for its first resolve of a known manga`() = testApplication {
		setup()
		user("first")
		assertEquals(HttpStatusCode.OK, resolve("first", "/manga/tower", "Tower of Mirrors").status)

		val second = user("second")
		spendQuota(second, left = 0)

		assertEquals(HttpStatusCode.TooManyRequests, resolve("second", "/manga/tower", "Tower of Mirrors").status)
	}
}
