package io.kotatsuredo.server

import io.kotatsuredo.server.catalogue.CatalogueLookup
import io.kotatsuredo.server.catalogue.CatalogueProvider
import io.kotatsuredo.server.catalogue.CatalogueRecord
import io.kotatsuredo.server.works.LinkOutcome
import io.kotatsuredo.server.works.ResolutionMethod
import io.kotatsuredo.server.works.WorkFingerprint
import io.kotatsuredo.server.works.WorkLinker
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkResolver
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkResolverTest {

	private val repository by lazy { WorkRepository(PostgresTestBase.database.source) }
	private val linker by lazy { WorkLinker(repository) }
	private val catalogueCalls = AtomicInteger()

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		catalogueCalls.set(0)
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE work, work_title, work_alias, work_cover_hash, work_external_id, " +
						"work_relation, work_merge_log, seed_progress CASCADE",
				)
			}
		}
	}

	/** A catalogue that knows one work, so the enrichment path can be exercised without network. */
	private fun catalogue(vararg titles: String) = CatalogueLookup(
		listOf(
			object : CatalogueProvider {
				override val name = "fake"
				override suspend fun lookup(title: String, year: Int?): CatalogueRecord? {
					catalogueCalls.incrementAndGet()
					return if (titles.isEmpty()) null else CatalogueRecord(
						provider = "fake",
						externalId = "1",
						canonicalTitle = titles.first(),
						titles = titles.toList(),
						year = 2018,
						contentType = "manga",
						nsfw = false,
						externalIds = mapOf("mal" to "116778"),
					)
				}
			},
		),
	)

	private fun resolver(vararg catalogueTitles: String) =
		WorkResolver(repository, catalogue(*catalogueTitles))

	private fun fingerprint(
		source: String,
		key: String = "/k/$source",
		title: String,
		alt: List<String> = emptyList(),
		year: Int? = 2018,
		phash: Long? = null,
		externalIds: Map<String, String> = emptyMap(),
	) = WorkFingerprint(source, key, title, alt, year, "manga", false, phash, externalIds)

	// -- the ladder ------------------------------------------------------------------------------

	@Test
	fun `an unknown manga is created from the catalogue with all its renderings`() = runTest {
		val resolution = resolver("Chainsaw Man", "Chainsawman", "チェンソーマン")
			.resolve(fingerprint("MANGADEX", title = "Chainsaw Man"))

		assertTrue(resolution.created)
		assertEquals(ResolutionMethod.CATALOGUE, resolution.method)
		assertEquals(1L, repository.countWorks())
	}

	/** The point of the whole design: a different source with a different spelling, same thread. */
	@Test
	fun `a second source using a different rendering resolves to the same work`() = runTest {
		val resolver = resolver("Chainsaw Man", "Chainsawman", "チェンソーマン")
		val first = resolver.resolve(fingerprint("MANGADEX", title = "Chainsaw Man"))
		val second = resolver.resolve(fingerprint("COMICK", title = "Chainsawman"))
		val third = resolver.resolve(fingerprint("SOME_JP_SOURCE", title = "チェンソーマン"))

		assertEquals(first.workId, second.workId)
		assertEquals(first.workId, third.workId)
		assertEquals(ResolutionMethod.EXACT_TITLE, second.method)
		assertEquals(1L, repository.countWorks(), "one work, three sources")
	}

	@Test
	fun `the second visit from the same source is an alias hit`() = runTest {
		val resolver = resolver("Chainsaw Man")
		resolver.resolve(fingerprint("MANGADEX", title = "Chainsaw Man"))
		val again = resolver.resolve(fingerprint("MANGADEX", title = "Chainsaw Man"))

		assertEquals(ResolutionMethod.ALIAS, again.method)
		assertEquals(1, catalogueCalls.get(), "an alias hit must not touch the catalogue")
	}

	@Test
	fun `a scrobbler id resolves even when the title is unrecognisable`() = runTest {
		val resolver = resolver("Chainsaw Man")
		val first = resolver.resolve(
			fingerprint("MANGADEX", title = "Chainsaw Man", externalIds = mapOf("mal" to "116778")),
		)
		val second = resolver.resolve(
			fingerprint("WEIRD", title = "CSM RAW v2 [Complete]", externalIds = mapOf("mal" to "116778")),
		)

		assertEquals(first.workId, second.workId)
		assertEquals(ResolutionMethod.EXTERNAL_ID, second.method)
	}

	/** Write-back: the awkward spelling is learned, so the next source using it hits exactly. */
	@Test
	fun `a rendering seen once is remembered for next time`() = runTest {
		val resolver = resolver("Chainsaw Man")
		resolver.resolve(
			fingerprint("SOURCE_A", title = "Chainsaw Man", alt = listOf("Denjimen no Hanashi")),
		)
		val later = resolver.resolve(fingerprint("SOURCE_B", key = "/b", title = "Denjimen no Hanashi"))

		assertEquals(ResolutionMethod.EXACT_TITLE, later.method)
		assertEquals(1L, repository.countWorks())
	}

	// -- guards ----------------------------------------------------------------------------------

	/** The failure §2.5 refuses to accept: a sequel inheriting its prequel's thread. */
	@Test
	fun `a sequel never resolves to the original`() = runTest {
		val resolver = resolver()
		val original = resolver.resolve(fingerprint("A", key = "/1", title = "Tower of God"))
		val sequel = resolver.resolve(fingerprint("A", key = "/2", title = "Tower of God Part 2"))

		assertNotEquals(original.workId, sequel.workId)
		assertEquals(2L, repository.countWorks())
	}

	@Test
	fun `an edition variant resolves to the original`() = runTest {
		val resolver = resolver()
		val original = resolver.resolve(fingerprint("A", key = "/1", title = "Berserk"))
		val colored = resolver.resolve(fingerprint("B", key = "/2", title = "Berserk (Colored)"))

		assertEquals(original.workId, colored.workId)
	}

	@Test
	fun `works decades apart with the same title stay separate`() = runTest {
		val resolver = resolver()
		val old = resolver.resolve(fingerprint("A", key = "/1", title = "Dragon Quest", year = 1989))
		val new = resolver.resolve(fingerprint("B", key = "/2", title = "Dragon Quest", year = 2016))

		assertNotEquals(old.workId, new.workId)
	}

	@Test
	fun `a matching cover confirms a fuzzy title match`() = runTest {
		val resolver = resolver()
		val first = resolver.resolve(
			fingerprint("A", key = "/1", title = "Kanojo mo Kanojo", phash = 0x0F0F_0F0F_0F0F_0F0FL),
		)
		// Same artwork (a few bits differ, as re-encoding does), title spelled differently.
		val second = resolver.resolve(
			fingerprint("B", key = "/2", title = "Kanojo mo Kanojo Girlfriend", phash = 0x0F0F_0F0F_0F0F_0F0EL),
		)

		assertEquals(first.workId, second.workId)
		assertEquals(ResolutionMethod.TITLE_AND_COVER, second.method)
	}

	@Test
	fun `an unrelated cover does not rescue an unrelated title`() = runTest {
		val resolver = resolver()
		val first = resolver.resolve(fingerprint("A", key = "/1", title = "Berserk", phash = 0L))
		val second = resolver.resolve(fingerprint("B", key = "/2", title = "One Piece", phash = -1L))

		assertNotEquals(first.workId, second.workId)
	}

	@Test
	fun `the catalogue is consulted once per work, not once per source`() = runTest {
		val resolver = resolver("Chainsaw Man", "Chainsawman")
		resolver.resolve(fingerprint("A", key = "/1", title = "Chainsaw Man"))
		resolver.resolve(fingerprint("B", key = "/2", title = "Chainsawman"))
		resolver.resolve(fingerprint("C", key = "/3", title = "Chainsaw Man"))

		assertEquals(1, catalogueCalls.get(), "catalogue called ${catalogueCalls.get()} times")
	}

	// -- user-confirmed links --------------------------------------------------------------------

	@Test
	fun `a user migration links a source that had its own work`() = runTest {
		val resolver = resolver()
		val a = resolver.resolve(fingerprint("A", key = "/1", title = "Some Manga"))
		val b = resolver.resolve(fingerprint("B", key = "/2", title = "Totally Different Rendering"))
		assertNotEquals(a.workId, b.workId)

		val outcome = linker.link("A" to "/1", "B" to "/2", "user_migration")

		assertIs<LinkOutcome.Merged>(outcome)
		// One work as far as anything can see. The losing row survives as a redirect so the merge can
		// be undone, which is what `countWorks` deliberately does not count.
		assertEquals(1L, repository.countWorks(), "the two works should now be one")
		assertEquals(outcome.into, repository.findByAlias("A", "/1"))
		assertEquals(outcome.into, repository.findByAlias("B", "/2"))
	}

	@Test
	fun `a merged work redirects so stale client caches can recover`() = runTest {
		val resolver = resolver()
		val a = resolver.resolve(fingerprint("A", key = "/1", title = "Some Manga"))
		val b = resolver.resolve(fingerprint("B", key = "/2", title = "Another Rendering"))
		val outcome = linker.link("A" to "/1", "B" to "/2", "user_migration")
		assertIs<LinkOutcome.Merged>(outcome)

		assertEquals(outcome.into, repository.mergedInto(outcome.from))
		assertFalse(a.workId == b.workId)
	}

	@Test
	fun `linking an unknown pair reports unknown rather than inventing a work`() = runTest {
		assertIs<LinkOutcome.Unknown>(linker.link("A" to "/nope", "B" to "/also-nope", "user_migration"))
		assertEquals(0L, repository.countWorks())
	}
}
