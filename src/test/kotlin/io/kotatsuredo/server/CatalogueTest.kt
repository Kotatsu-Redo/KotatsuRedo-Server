package io.kotatsuredo.server

import io.kotatsuredo.server.catalogue.CatalogueLookup
import io.kotatsuredo.server.catalogue.CatalogueLookupOverloaded
import io.kotatsuredo.server.catalogue.CatalogueOutcome
import io.kotatsuredo.server.catalogue.CatalogueProvider
import io.kotatsuredo.server.catalogue.CatalogueUnavailable
import io.kotatsuredo.server.catalogue.CatalogueRecord
import io.kotatsuredo.server.catalogue.HttpFetcher
import io.kotatsuredo.server.catalogue.KitsuCatalogue
import io.kotatsuredo.server.catalogue.MangaUpdatesCatalogue
import io.kotatsuredo.server.catalogue.canonicalMangaUpdatesId
import io.kotatsuredo.server.catalogue.MangaDexCatalogue
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
		return HttpFetcher { _, _, _ -> remaining.removeFirstOrNull() }
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

	/**
	 * Kitsu spells a MangaUpdates id the way the website's URL does - base36 - and the MangaUpdates
	 * API answers in decimal. Verified against the live pair: `/series/xpnodiq/` is series
	 * `73385239922`. Stored unconverted, the same series arriving from the two providers looks like
	 * two different works, forever.
	 */
	@Test
	fun `a mangaupdates id from another catalogue is stored the way mangaupdates writes it`() {
		assertEquals("73385239922", canonicalMangaUpdatesId("xpnodiq"))
		assertEquals("4202565175", canonicalMangaUpdatesId("1xi3k1j"))
		// Already decimal, or not a base36 word at all: left exactly as it arrived.
		assertEquals("44280046645", canonicalMangaUpdatesId("44280046645"))
		assertEquals("not-an-id", canonicalMangaUpdatesId("not-an-id"))
		assertEquals("", canonicalMangaUpdatesId(""))
	}

	/**
	 * Kitsu speaks JSON:API and answers `406 Not Acceptable` to anything asking for plain
	 * `application/json`. Every lookup failed that way in production for months, and nothing showed
	 * it: a catalogue refusing every request is indistinguishable from one that lists nothing, so the
	 * works were quietly created from the source's own title instead.
	 */
	@Test
	fun `kitsu asks for the media type kitsu actually serves`() = runTest {
		var asked: String? = null
		val catalogue = KitsuCatalogue(
			HttpFetcher { _, _, accept ->
				asked = accept
				fixture("kitsu-chainsaw-man")
			},
		)

		assertNotNull(catalogue.lookup("Chainsaw Man"))
		assertEquals("application/vnd.api+json", asked, "a plain application/json request is a 406")
	}

	/**
	 * The Past Life Returner case, from the live response: Kitsu answers with the *novel* first and
	 * the manhwa second. Handing a comic source the novel creates a work no comic source can ever
	 * match again, and every later source splits off its own.
	 */
	@Test
	fun `kitsu skips a novel when the source is showing a comic`() {
		val catalogue = KitsuCatalogue(fetcherReturning())
		val fixture = fixture("kitsu-past-life-returner")

		val comic = catalogue.parse(fixture, "Past Life Returner", contentType = "manga")
		assertNotNull(comic)
		assertEquals("manhwa", comic.contentType)
		assertEquals("Jeonsaengja", comic.canonicalTitle)

		// Asked about as a novel - or not told at all - the novel is still the right answer.
		assertEquals("novel", catalogue.parse(fixture, "Past Life Returner", contentType = "novel")?.contentType)
		assertEquals("novel", catalogue.parse(fixture, "Past Life Returner")?.contentType)
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

	// -- MangaDex --------------------------------------------------------------------------------

	/**
	 * The reason MangaDex is asked first: one request, four cross-provider ids. Those ids are what
	 * turn two works found by two sources into one work, which nothing else provides in a single call.
	 */
	@Test
	fun `mangadex returns the ids that let duplicates be joined`() {
		val record = MangaDexCatalogue(fetcherReturning())
			.parse(fixture("mangadex-chainsaw-man"), "Chainsaw Man", contentType = "manga")

		assertNotNull(record)
		assertEquals("116778", record.externalIds["mal"])
		assertEquals("105778", record.externalIds["anilist"])
		assertEquals("54139", record.externalIds["kitsu"])
		assertNotNull(record.externalIds["mangadex"])
		// MangaDex spells a MangaUpdates id in base36 exactly as Kitsu does, so it needs the same
		// conversion or it can never meet the same id arriving from MangaUpdates itself.
		assertEquals(canonicalMangaUpdatesId("ylx5wzn"), record.externalIds["mangaupdates"])
		assertTrue(record.externalIds.getValue("mangaupdates").all(Char::isDigit))
	}

	/**
	 * Searching the live API for `Chainsaw Man` answers with the short story collection first and the
	 * coloured edition second - and the edition normalizes to the same key as the work itself, so
	 * "first title that matches" picks the thin record over the one carrying every id.
	 */
	@Test
	fun `mangadex prefers the candidate carrying the ids over an edition of it`() {
		val record = MangaDexCatalogue(fetcherReturning())
			.parse(fixture("mangadex-chainsaw-man"), "Chainsaw Man", contentType = "manga")

		assertNotNull(record)
		assertEquals(2018, record.year, "the 2018 work, not the 2021 colouring")
		assertTrue(record.externalIds.size >= 5, "picked a thinner record: ${record.externalIds}")
	}

	@Test
	fun `mangadex reads the work's type from the language it was drawn in`() {
		val record = MangaDexCatalogue(fetcherReturning())
			.parse(fixture("mangadex-chainsaw-man"), "Chainsaw Man")

		assertNotNull(record)
		assertEquals("manga", record.contentType)
	}

	@Test
	fun `mangadex rejects a result that matches nothing we asked for`() {
		val record = MangaDexCatalogue(fetcherReturning())
			.parse(fixture("mangadex-chainsaw-man"), "Something Entirely Different")

		assertNull(record, "matched the wrong work: ${record?.canonicalTitle}")
	}

	/** An unreachable MangaDex must never look like a title nothing lists. */
	@Test
	fun `mangadex reports a failed request as unavailable`() = runTest {
		val catalogue = MangaDexCatalogue(HttpFetcher { _, _, _ -> null }, minIntervalMillis = 0)

		assertFailsWith<CatalogueUnavailable> { catalogue.lookup("Chainsaw Man") }
	}

	// -- lookup orchestration --------------------------------------------------------------------

	private fun provider(name: String, record: CatalogueRecord?, calls: AtomicInteger) =
		object : CatalogueProvider {
			override val name = name
			override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord? {
				calls.incrementAndGet()
				return record
			}
		}

	private fun record(title: String) = CatalogueRecord(
		provider = "test", externalId = "1", canonicalTitle = title, titles = listOf(title),
		year = null, contentType = null, nsfw = false, externalIds = emptyMap(),
	)

	/**
	 * Each catalogue knows identifiers the others do not, and it is having all of them that lets two
	 * works found by two sources turn out to be one. Stopping at the first answer leaves a work with
	 * one id and no way to meet its own duplicate.
	 */
	@Test
	fun `every catalogue is asked and their identifiers are pooled`() = runTest {
		val first = AtomicInteger()
		val second = AtomicInteger()
		val lookup = CatalogueLookup(
			listOf(
				provider("first", record("Chainsaw Man").copy(externalIds = mapOf("mal" to "116778")), first),
				provider(
					"second",
					record("Chainsawman").copy(externalIds = mapOf("mangaupdates" to "44280046645")),
					second,
				),
			),
		)

		val found = lookup.lookup("Chainsaw Man")

		assertNotNull(found)
		assertEquals(1, first.get())
		assertEquals(1, second.get(), "the second catalogue must be asked too")
		assertEquals("116778", found.externalIds["mal"])
		assertEquals("44280046645", found.externalIds["mangaupdates"])
		assertTrue(found.titles.containsAll(listOf("Chainsaw Man", "Chainsawman")), "titles: ${found.titles}")
		assertEquals("Chainsaw Man", found.canonicalTitle, "the first to answer names the work")
	}

	/** What the reachable ones knew is worth keeping - and worth asking again once the other is back. */
	@Test
	fun `an answer assembled while one catalogue was down is marked partial`() = runTest {
		val lookup = CatalogueLookup(
			listOf(
				provider("healthy", record("Chainsaw Man"), AtomicInteger()),
				object : CatalogueProvider {
					override val name = "down"
					override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord =
						throw CatalogueUnavailable(name)
				},
			),
		)

		val outcome = lookup.lookupOutcome("Chainsaw Man")

		assertIs<CatalogueOutcome.Found>(outcome)
		assertTrue(outcome.partial, "one provider never answered, so this is not the whole story")
	}

	/**
	 * Order is preference, not precedence: the first to answer names the work, and the others still
	 * contribute what they know. It used to stop here, which cost the ids the later ones carry.
	 */
	@Test
	fun `the first catalogue to answer names the work`() = runTest {
		val first = AtomicInteger()
		val second = AtomicInteger()
		val lookup = CatalogueLookup(
			listOf(provider("first", record("Found"), first), provider("second", record("X"), second)),
		)

		val found = lookup.lookup("Chainsaw Man")

		assertNotNull(found)
		assertEquals("Found", found.canonicalTitle)
		assertEquals(1, first.get())
		assertEquals(1, second.get())
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
			override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord? {
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
			override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord {
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

	/**
	 * An outage says nothing about the title. Remembering it as a miss is how a few minutes of
	 * downtime becomes a permanent provisional work for every manga opened during it.
	 */
	@Test
	fun `a provider that could not answer is not remembered as a miss`() = runTest {
		val calls = AtomicInteger()
		val flaky = object : CatalogueProvider {
			override val name = "flaky"
			override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord? {
				if (calls.incrementAndGet() == 1) throw CatalogueUnavailable(name)
				return record(title)
			}
		}
		val lookup = CatalogueLookup(listOf(flaky))

		assertNull(lookup.lookup("Briefly Unreachable"))
		assertNotNull(lookup.lookup("Briefly Unreachable"), "an outage must not become a permanent miss")
		assertEquals(2, calls.get())
	}

	@Test
	fun `a title asked about as two content types is looked up for each`() = runTest {
		val calls = AtomicInteger()
		val lookup = CatalogueLookup(listOf(provider("only", null, calls)))

		assertNull(lookup.lookup("Same Name", contentType = "manga"))
		assertNull(lookup.lookup("Same Name", contentType = "novel"))
		assertNull(lookup.lookup("Same Name", contentType = "manga"))

		assertEquals(2, calls.get(), "each content type is its own question, and each miss its own entry")
	}

	@Test
	fun `a provider that throws does not break resolution`() = runTest {
		val healthy = AtomicInteger()
		val exploding = object : CatalogueProvider {
			override val name = "exploding"
			override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord =
				throw IllegalStateException("catalogue is down")
		}
		val lookup = CatalogueLookup(listOf(exploding, provider("healthy", record("Found"), healthy)))

		assertNotNull(lookup.lookup("Chainsaw Man"))
		assertEquals(1, healthy.get())
	}
}
