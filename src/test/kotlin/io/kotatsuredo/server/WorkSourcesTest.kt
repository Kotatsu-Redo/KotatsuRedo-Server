package io.kotatsuredo.server

import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.works.WorkRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which sources carry a work, from observations the resolver already writes.
 *
 * The property that matters as much as the answer: the reply is aggregate. One account's reading
 * list must never be readable back out of it.
 */
class WorkSourcesTest {

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
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE work, work_title, work_alias, work_alias_observation, work_external_id, " +
						"work_merge_log, app_user, user_active_day, app_device CASCADE",
				)
			}
		}
	}

	private fun user(seed: String): String {
		val outcome = identities.hello(seed, DeviceIdentifiers("dev-$seed", null))
		return (outcome as HelloOutcome.Ok).identity.id
	}

	private fun sanction(userId: String) {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement("UPDATE app_user SET is_banned = TRUE WHERE id = ?").use {
				it.setString(1, userId)
				it.executeUpdate()
			}
		}
	}

	private fun seen(source: String, key: String, workId: Long, vararg users: String) {
		users.forEach { repository.observeAlias(source, key, it, workId, listOf("key")) }
	}

	@Test
	fun `a source two accounts have seen the work on is reported`() {
		val workId = repository.createWork("Some Manga", 2019, "manga", false)
		seen("MANGADEX", "abc", workId, user("a"), user("b"))

		val sources = repository.sourcesFor(workId)

		assertEquals(1, sources.size)
		assertEquals("MANGADEX", sources.single().source)
		assertEquals("abc", sources.single().sourceKey, "the key is what lets the app open it there")
		assertEquals(2, sources.single().reporters)
	}

	/** One observation is one person's library, so it is never enough to report a source. */
	@Test
	fun `a source only one account has seen is not reported`() {
		val workId = repository.createWork("Some Manga", 2019, "manga", false)
		seen("LONELY", "solo", workId, user("a"))

		assertTrue(repository.sourcesFor(workId).isEmpty(), "one account cannot be read back out")
	}

	@Test
	fun `banned accounts do not vouch for a source`() {
		val workId = repository.createWork("Some Manga", 2019, "manga", false)
		val banned = user("banned")
		seen("SHADY", "x", workId, user("honest"), banned)
		sanction(banned)

		assertTrue(repository.sourcesFor(workId).isEmpty(), "one honest account is left, which is not enough")
	}

	@Test
	fun `the best corroborated source comes first`() {
		val workId = repository.createWork("Some Manga", 2019, "manga", false)
		seen("POPULAR", "p", workId, user("a"), user("b"), user("c"))
		seen("NICHE", "n", workId, user("d"), user("e"))

		assertEquals(listOf("POPULAR", "NICHE"), repository.sourcesFor(workId).map { it.source })
	}

	/** Coverage is per work: a source carrying one manga says nothing about another. */
	@Test
	fun `sources are not shared between works`() {
		val one = repository.createWork("One", 2019, "manga", false)
		val other = repository.createWork("Other", 2020, "manga", false)
		seen("HAS_ONE", "k1", one, user("a"), user("b"))

		assertEquals(1, repository.sourcesFor(one).size)
		assertTrue(repository.sourcesFor(other).isEmpty())
	}

	@Test
	fun `the quorum that verifies an alias is reported as verified`() {
		val workId = repository.createWork("Some Manga", 2019, "manga", false)
		seen("MANGADEX", "abc", workId, user("a"), user("b"))

		// Two accounts are enough to report the source and short of the resolver's own quorum.
		assertEquals(false, repository.sourcesFor(workId).single().verified)
	}
}
