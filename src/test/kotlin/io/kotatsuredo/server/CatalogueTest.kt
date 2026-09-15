package io.kotatsuredo.server

import io.kotatsuredo.server.catalogue.CatalogueLookup
import io.kotatsuredo.server.catalogue.CatalogueLookupOverloaded
import io.kotatsuredo.server.catalogue.CatalogueProvider
import io.kotatsuredo.server.catalogue.CatalogueRecord
import io.kotatsuredo.server.catalogue.HttpFetcher
import io.kotatsuredo.server.catalogue.KitsuCatalogue
import io.kotatsuredo.server.catalogue.MangaUpdatesCatalogue
import io.kotatsuredo.server.works.TitleNormalizer
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsing is tested against **real captured responses**, not hand-written JSON: a fixture someone
 * invented tests the parser against their idea of the API, which is exactly where these things break.
 */
class CatalogueTest {

	private fun fixture(name: String): String =
		requireNotNull(javaClass.getResourceAsStream("/catalogue/$name.json")) { "missing fixture $name" }
			.bufferedReader().readText()

	private fun fetcherReturning(vararg bodies: String): HttpFetcher {
		val remaining = bodies.toMutableList()
		return HttpFetcher { _, _ -> remaining.removeFirstOrNull() }
	}

	// -- Kitsu -----------------------------------------------------------------------------------

	@Test
	fun `kitsu yields the title renderings that make resolution work`() {
		val record = KitsuCatalogue(fetcherReturning()).parse(fixture("kitsu-chainsaw-man"), "Chainsaw Man")

		assertNotNull(record)
		assertEquals("Chainsaw Man", record.canonicalTitle)
		assertTrue(record.titles.size >= 10, "only ${record.titles.size} titles: ${record.titles}")

		// The whole point of preferring a rich catalogue: these are separate sources' spellings.
		val keys = record.titles.flatMap { TitleNormalizer.keys(it) }.toSet()
		listOf("Chainsaw Man", "Chainsawman", "チェンソーマン").forEach { rendering ->
			assertTrue(
				TitleNormalizer.keys(rendering).any { it in keys },
				"'$rendering' should be reachable from the record, keys=${record.titles}",
			)
		}
	}

	@Test
	fun `kitsu carries the external ids that anchor scrobbler-linked clients`() {
		val record = KitsuCatalogue(fetcherReturning()).parse(fixture("kitsu-chainsaw-man"), "Chainsaw Man")

		assertNotNull(record)
		assertEquals("116778", record.externalIds["mal"])
		assertEquals("105778", record.externalIds["anilist"])
		assertNotNull(record.externalIds["mangaupdates"], "mappings: ${record.externalIds}")
	}

	@Test
	fun `kitsu parses a second unrelated work`() {
		val record = KitsuCatalogue(fetcherReturning()).parse(fixture("kitsu-solo-leveling"), "Solo Leveling")

		assertNotNull(record)
		assertTrue(record.titles.isNotEmpty())
		assertNotNull(record.year)
	}

	/** Kitsu's text filter is fuzzy, so the top hit is not automatically the right work. */
	@Test
	fun `kitsu rejects a result that matches nothing we asked for`() {
		val record = KitsuCatalogue(fetcherReturning())
			.parse(fixture("kitsu-chainsaw-man"), "Something Entirely Different")

		assertNull(record, "matched the wrong work: ${record?.canonicalTitle}")
	}

	// -- MangaUpdates ----------------------------------------------------------------------------

	@Test
	fun `mangaupdates picks the right series then reads its associated names`() {
		val catalogue = MangaUpdatesCatalogue(fetcherReturning())

		val seriesId = catalogue.pickSeries(fixture("mangaupdates-search"), "Chainsaw Man", year = 2018)
		assertNotNull(seriesId)

		val record = catalogue.parseSeries(fixture("mangaupdates-series"))
		assertNotNull(record)
		assertEquals(2018, record.year)
		assertTrue(record.titles.size >= 5, "associated names missing: ${record.titles}")

		val keys = record.titles.flatMap { TitleNormalizer.keys(it) }.toSet()
		assertTrue(
			TitleNormalizer.keys("Chainsawman").any { it in keys },
			"the segmentation variant should be reachable: ${record.titles}",
		)
	}

	@Test
	fun `mangaupdates keeps story relations and drops edition variants`() {
		val record = MangaUpdatesCatalogue(fetcherReturning()).parseSeries(fixture("mangaupdates-series"))

		assertNotNull(record)
		// Whatever this series has, none of it may be an edition variant: those merge (PLAN.md 2.5),
		// and recording one as a relation would keep two works apart that should be one.
		assertTrue(
			record.relations.none { it.type == "colored" || it.type == "alternative_edition" },
			"edition variants must not become relations: ${record.relations}",
		)
	}

	// -- lookup orchestration --------------------------------------------------------------------

	private fun provider(name: String, record: CatalogueRecord?, calls: AtomicInteger) =
		object : CatalogueProvider {
			override val name = name
			override suspend fun lookup(title: String, year: Int?): CatalogueRecord? {
				calls.incrementAndGet()
				return record
			}
		}

	private fun record(title: String) = CatalogueRecord(
		provider = "test", externalId = "1", canonicalTitle = title, titles = listOf(title),
		year = null, contentType = null, nsfw = false, externalIds = emptyMap(),
	)

	@Test
	fun `the second catalogue is only consulted when the first misses`() = runTest {
		val first = AtomicInteger()
		val second = AtomicInteger()
		val lookup = CatalogueLookup(
			listOf(provider("first", record("Found"), first), provider("second", record("X"), second)),
		)

		assertNotNull(lookup.lookup("Chainsaw Man"))
		assertEquals(1, first.get())
		assertEquals(0, second.get(), "the fallback catalogue should not have been called")
	}

	@Test
	fun `it falls through to the fallback catalogue`() = runTest {
		val first = AtomicInteger()
		val second = AtomicInteger()
		val lookup = CatalogueLookup(
			listOf(provider("first", null, first), provider("second", record("Found"), second)),
		)

		assertNotNull(lookup.lookup("Obscure Manhwa"))
		assertEquals(1, second.get())
	}

	/**
	 * A manga missing from every catalogue must cost one round trip ever, not one per user who opens
	 * it. Without this the long tail - which is most of 1200 sources - would generate endless
	 * pointless traffic to catalogues doing us a favour by being open.
	 */
	@Test
	fun `a miss is remembered so it is never looked up twice`() = runTest {
		val calls = AtomicInteger()
		val lookup = CatalogueLookup(listOf(provider("only", null, calls)))

		repeat(5) { assertNull(lookup.lookup("Some Untracked Doujin")) }

		assertEquals(1, calls.get(), "looked up a known miss ${calls.get()} times")
	}

	@Test
	fun `concurrent lookups for one title share one provider request`() = runTest {
		val calls = AtomicInteger()
		val slow = object : CatalogueProvider {
			override val name = "slow"
			override suspend fun lookup(title: String, year: Int?): CatalogueRecord? {
				calls.incrementAndGet()
				delay(100)
				return record(title)
			}
		}
		val lookup = CatalogueLookup(listOf(slow))

		val results = coroutineScope {
			List(20) { async { lookup.lookup("One Shared Title") } }.awaitAll()
		}

		assertTrue(results.all { it != null })
		assertEquals(1, calls.get())
	}

	@Test
	@OptIn(ExperimentalCoroutinesApi::class)
	fun `lookup admission is bounded and overload is not negative cached`() = runTest {
		val release = CompletableDeferred<Unit>()
		val calls = AtomicInteger()
		val slow = object : CatalogueProvider {
			override val name = "slow"
			override suspend fun lookup(title: String, year: Int?): CatalogueRecord {
				calls.incrementAndGet()
				release.await()
				return record(title)
			}
		}
		val lookup = CatalogueLookup(listOf(slow), maxConcurrent = 1, maxQueued = 1)

		val first = async { lookup.lookup("First") }
		runCurrent()
		val second = async { lookup.lookup("Second") }
		runCurrent()
		assertEquals(1, calls.get(), "one request should run while one waits")

		var overloaded = false
		try {
			lookup.lookup("Third")
		} catch (_: CatalogueLookupOverloaded) {
			overloaded = true
		}
		assertTrue(overloaded, "excess unique work must fail fast without pretending to be a miss")
		assertEquals(1, calls.get())

		release.complete(Unit)
		assertNotNull(first.await())
		assertNotNull(second.await())
		assertNotNull(lookup.lookup("Third"), "overload must not become a permanent negative-cache entry")
		assertEquals(3, calls.get())
	}

	@Test
	fun `a provider that throws does not break resolution`() = runTest {
		val healthy = AtomicInteger()
		val exploding = object : CatalogueProvider {
			override val name = "exploding"
			override suspend fun lookup(title: String, year: Int?): CatalogueRecord =
				throw IllegalStateException("catalogue is down")
		}
		val lookup = CatalogueLookup(listOf(exploding, provider("healthy", record("Found"), healthy)))

		assertNotNull(lookup.lookup("Chainsaw Man"))
		assertEquals(1, healthy.get())
	}
}
