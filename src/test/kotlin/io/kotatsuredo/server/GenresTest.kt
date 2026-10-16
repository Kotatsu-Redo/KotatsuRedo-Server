package io.kotatsuredo.server

import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.works.Genres
import io.kotatsuredo.server.works.WorkRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Genres arrive as user-supplied strings from a thousand parsers, so two things carry the whole
 * design: nothing outside the vocabulary is ever stored, and nothing a single account says is ever
 * published.
 */
class GenresTest {

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
					"TRUNCATE work, work_title, work_genre, work_genre_observation, work_alias, " +
						"work_alias_observation, app_user, app_device CASCADE",
				)
			}
		}
	}

	private fun user(seed: String): String {
		val outcome = identities.hello(seed, DeviceIdentifiers("dev-$seed", null))
		return (outcome as HelloOutcome.Ok).identity.id
	}

	private fun work() = repository.createWork("Some Manga", 2019, "manga", false)

	// -- the vocabulary ----------------------------------------------------------------------------

	@Test
	fun `the same genre written five ways lands on one term`() {
		listOf("Sci-Fi", "sci fi", "Science Fiction", "SCIFI", "  sf  ").forEach {
			assertEquals("sci-fi", Genres.parse(it), "failed on '$it'")
		}
		listOf("Yaoi", "Shounen Ai", "BL", "Boys' Love").forEach {
			assertEquals("boys-love", Genres.parse(it), "failed on '$it'")
		}
	}

	@Test
	fun `anything outside the vocabulary is dropped rather than stored`() {
		listOf("long strip", "webtoon", "full color", "", "   ", "a slur or anything else at all")
			.forEach { assertNull(Genres.parse(it), "should not be recognised: '$it'") }
	}

	@Test
	fun `a source's whole list is mapped, deduplicated and capped`() {
		val parsed = Genres.parseAll(
			listOf("Action", "action", "ACTION", "Webtoon", "Romance", "Drama", "Nonsense"),
		)

		assertEquals(listOf("action", "romance", "drama"), parsed)
		assertTrue(Genres.parseAll(List(50) { "genre-$it" } + List(50) { "action" }).size <= Genres.MAX_GENRES_PER_WORK)
	}

	// -- agreement ---------------------------------------------------------------------------------

	@Test
	fun `one account's genres are recorded but not published`() {
		val workId = work()

		assertEquals(emptyList(), repository.observeGenres(workId, user("a"), listOf("Action", "Romance")))
		assertEquals(emptyList(), repository.genresOf(workId))
	}

	@Test
	fun `a genre three accounts agree on is published`() {
		val workId = work()
		repository.observeGenres(workId, user("a"), listOf("Action", "Romance"))
		repository.observeGenres(workId, user("b"), listOf("Action"))
		val published = repository.observeGenres(workId, user("c"), listOf("Action"))

		assertEquals(listOf("action"), published)
		assertEquals(listOf("action"), repository.genresOf(workId), "romance has one voice, not three")
	}

	@Test
	fun `one account cannot reach the quorum alone`() {
		val workId = work()
		val alone = user("a")
		repeat(5) { repository.observeGenres(workId, alone, listOf("Horror")) }

		assertEquals(emptyList(), repository.genresOf(workId))
	}

	@Test
	fun `sanctioned accounts do not count toward agreement`() {
		val workId = work()
		val banned = user("banned")
		repository.observeGenres(workId, user("a"), listOf("Mecha"))
		repository.observeGenres(workId, user("b"), listOf("Mecha"))
		repository.observeGenres(workId, banned, listOf("Mecha"))
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement("UPDATE app_user SET is_banned = TRUE WHERE id = ?").use {
				it.setString(1, banned)
				it.executeUpdate()
			}
		}
		repository.observeGenres(workId, user("d"), listOf("Mecha"))

		assertEquals(listOf("mecha"), repository.genresOf(workId), "the three honest accounts carry it")
	}

	// -- catalogue ---------------------------------------------------------------------------------

	@Test
	fun `catalogue genres are published without a quorum`() {
		val workId = work()
		repository.applyCatalogueGenres(workId, listOf("Psychological", "Drama", "Long Strip"))

		assertEquals(listOf("drama", "psychological"), repository.genresOf(workId).sorted())
	}

	@Test
	fun `catalogue-backed genres come first`() {
		val workId = work()
		repository.observeGenres(workId, user("a"), listOf("Comedy"))
		repository.observeGenres(workId, user("b"), listOf("Comedy"))
		repository.observeGenres(workId, user("c"), listOf("Comedy"))
		repository.applyCatalogueGenres(workId, listOf("Horror"))

		assertEquals(listOf("horror", "comedy"), repository.genresOf(workId))
	}

	/**
	 * Sources tag generously, and a fingerprint is how a work gets its ratings and comments. Extra
	 * labels are dropped, never a reason to refuse the work.
	 */
	@Test
	fun `a generously tagged work keeps only what the vocabulary knows`() {
		val workId = work()
		val sourceLabels = List(60) { "Made Up Label $it" } + listOf("Action", "Romance", "Drama")
		listOf("a", "b", "c").forEach { repository.observeGenres(workId, user(it), sourceLabels) }

		assertEquals(listOf("action", "drama", "romance"), repository.genresOf(workId).sorted())
	}

	// -- similarity --------------------------------------------------------------------------------

	private fun workNamed(title: String) = repository.createWork(title, 2019, "manga", false)

	/** A shared rare genre says far more than a shared common one, which is the whole ranking. */
	@Test
	fun `a work sharing rare genres outranks one sharing only common ones`() {
		val seed = workNamed("Seed")
		repository.applyCatalogueGenres(seed, listOf("Action", "Villainess", "Isekai"))

		val close = workNamed("Close")
		repository.applyCatalogueGenres(close, listOf("Villainess", "Isekai"))
		val loose = workNamed("Loose")
		repository.applyCatalogueGenres(loose, listOf("Action", "Comedy"))
		// "action" is everywhere, so it is worth little.
		repeat(30) { index ->
			repository.applyCatalogueGenres(workNamed("Filler $index"), listOf("Action", "Comedy"))
		}

		val similar = repository.similarTo(seed)

		assertEquals("Close", similar.first().title, "ranked: ${similar.map { it.title }}")
		assertTrue(similar.none { it.title == "Seed" }, "a work is not similar to itself")
	}

	@Test
	fun `one genre in common is not similarity`() {
		val seed = workNamed("Seed")
		repository.applyCatalogueGenres(seed, listOf("Horror", "Mystery"))
		val stranger = workNamed("Stranger")
		repository.applyCatalogueGenres(stranger, listOf("Horror", "Sports"))

		assertTrue(repository.similarTo(seed).isEmpty(), "one shared genre is a coincidence")
	}

	@Test
	fun `a work merged away is never suggested`() {
		val seed = workNamed("Seed")
		repository.applyCatalogueGenres(seed, listOf("Mecha", "Military"))
		val duplicate = workNamed("Duplicate")
		repository.applyCatalogueGenres(duplicate, listOf("Mecha", "Military"))
		val survivor = workNamed("Survivor")
		repository.mergeWorks(from = duplicate, into = survivor, reason = "test")

		assertTrue(repository.similarTo(seed).none { it.workId == duplicate }, "merged works are gone")
	}

	@Test
	fun `genres follow a work into the one it is merged into`() {
		val duplicate = work()
		val survivor = repository.createWork("Some Manga (dup)", 2019, "manga", false)
		repository.applyCatalogueGenres(duplicate, listOf("Isekai"))

		repository.mergeWorks(from = duplicate, into = survivor, reason = "test")

		assertEquals(listOf("isekai"), repository.genresOf(survivor))
	}
}
