package io.kotatsuredo.server

import io.kotatsuredo.server.catalogue.CatalogueLookup
import io.kotatsuredo.server.catalogue.CatalogueProvider
import io.kotatsuredo.server.catalogue.CatalogueRecord
import io.kotatsuredo.server.ratings.RatingRepository
import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.works.LinkOutcome
import io.kotatsuredo.server.works.ResolutionMethod
import io.kotatsuredo.server.works.WorkFingerprint
import io.kotatsuredo.server.works.WorkLinker
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkEnricher
import io.kotatsuredo.server.works.WorkResolver
import io.kotatsuredo.server.works.WorkResolutionOverloaded
import io.kotatsuredo.server.works.TitleNormalizer
import io.kotatsuredo.server.works.TitleToStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.sql.SQLException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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
				override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord? {
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

	private fun resolver(vararg catalogueTitles: String) = WorkResolver(repository)

	/** The enricher the resolver now hands unknown works to, wired to the fake catalogue above. */
	private fun enricher(vararg catalogueTitles: String) =
		WorkEnricher(repository, catalogue(*catalogueTitles), ratings = null)

	private fun fingerprint(
		source: String,
		key: String = "/k/$source",
		title: String,
		alt: List<String> = emptyList(),
		year: Int? = 2018,
		phash: Long? = null,
		externalIds: Map<String, String> = emptyMap(),
		contentType: String = "manga",
	) = WorkFingerprint(source, key, title, alt, year, contentType, false, phash, externalIds)

	private fun reporter(id: String, settled: Boolean = true) {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.autoCommit = false
			try {
			connection.prepareStatement(
				"""
				INSERT INTO app_user (id, secret_sha256, created_at, is_banned, is_shadowbanned)
				VALUES (?, ?, CASE WHEN ? THEN now() - INTERVAL '91 days' ELSE now() END, FALSE, FALSE)
				ON CONFLICT (id) DO UPDATE SET
					created_at = EXCLUDED.created_at, is_banned = FALSE, is_shadowbanned = FALSE
				""".trimIndent(),
			).use {
				it.setString(1, id)
				it.setBytes(2, id.padEnd(32, '_').take(32).encodeToByteArray())
				it.setBoolean(3, settled)
				it.executeUpdate()
			}
				connection.prepareStatement("DELETE FROM user_active_day WHERE user_id = ?").use {
					it.setString(1, id)
					it.executeUpdate()
				}
				connection.prepareStatement(
					"""
					INSERT INTO user_active_day (user_id, day)
					SELECT ?, CURRENT_DATE - n::int
					FROM generate_series(0, CASE WHEN ? THEN 29 ELSE 0 END) AS n
					""".trimIndent(),
				).use {
					it.setString(1, id)
					it.setBoolean(2, settled)
					it.executeUpdate()
				}
				connection.commit()
			} catch (error: Exception) {
				connection.rollback()
				throw error
			} finally {
				connection.autoCommit = true
			}
		}
	}

	private fun normalReporter(id: String) {
		reporter(id)
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"UPDATE app_user SET created_at = now() - INTERVAL '30 days' WHERE id = ?",
			).use {
				it.setString(1, id)
				it.executeUpdate()
			}
		}
	}

	// -- the ladder ------------------------------------------------------------------------------

	@Test
	fun `an unknown manga is created at once and the catalogue fills it in afterwards`() = runTest {
		// The reader never waits on Kitsu: the work exists as soon as the request is answered.
		val resolution = resolver().resolve(fingerprint("MANGADEX", title = "Chainsaw Man"))

		assertTrue(resolution.created)
		assertEquals(ResolutionMethod.CREATED, resolution.method)
		assertEquals(1L, repository.countWorks())
		assertEquals(0, catalogueCalls.get(), "resolution must not consult a catalogue")

		val report = enricher("Chainsaw Man", "Chainsawman", "チェンソーマン").drainOnce()

		assertEquals(1, report.enriched)
		val titles = repository.titlesOf(resolution.workId)
		assertTrue(
			TitleNormalizer.keys("Chainsawman").any { it in titles },
			"the catalogue's renderings should now be indexed: " + titles,
		)
	}

	/** The point of the whole design: a different source with a different spelling, same thread. */
	@Test
	fun `a second source using a different rendering resolves to the same work`() = runTest {
		val resolver = resolver()
		val first = resolver.resolve(fingerprint("MANGADEX", title = "Chainsaw Man"))
		// The renderings arrive with enrichment, which is what lets the other spellings land here.
		enricher("Chainsaw Man", "Chainsawman", "チェンソーマン").drainOnce()
		val second = resolver.resolve(fingerprint("COMICK", title = "Chainsawman"))
		val third = resolver.resolve(fingerprint("SOME_JP_SOURCE", title = "チェンソーマン"))

		assertEquals(first.workId, second.workId)
		assertEquals(first.workId, third.workId)
		assertEquals(ResolutionMethod.EXACT_TITLE, second.method)
		assertEquals(1L, repository.countWorks(), "one work, three sources")
	}

	@Test
	fun `the second visit from the same source is an alias hit`() = runTest {
		val resolver = resolver()
		resolver.resolve(fingerprint("MANGADEX", title = "Chainsaw Man"))
		val again = resolver.resolve(fingerprint("MANGADEX", title = "Chainsaw Man"))

		assertEquals(ResolutionMethod.ALIAS, again.method)
		assertEquals(0, catalogueCalls.get(), "no resolve may touch the catalogue")
	}

	@Test
	fun `public aliases require five settled reporters and conflicting titles stay separate`() = runTest {
		listOf("reporter-a", "reporter-b", "reporter-c", "reporter-d", "reporter-e", "reporter-f")
			.forEach(::reporter)
		val resolver = WorkResolver(repository)
		val key = "/shared/source-key"

		val first = resolver.resolve(fingerprint("SOURCE", key, "Good Work"), "reporter-a")
		val second = resolver.resolve(fingerprint("SOURCE", key, "Good Work"), "reporter-b")
		assertEquals(first.workId, second.workId)
		assertEquals(null, repository.findVerifiedAlias("SOURCE", key))

		val conflicting = resolver.resolve(fingerprint("SOURCE", key, "Different Work"), "reporter-f")
		assertNotEquals(first.workId, conflicting.workId)

		listOf("reporter-c", "reporter-d").forEach {
			assertEquals(first.workId, resolver.resolve(fingerprint("SOURCE", key, "Good Work"), it).workId)
		}
		assertEquals(null, repository.findVerifiedAlias("SOURCE", key))
		val fifth = resolver.resolve(fingerprint("SOURCE", key, "Good Work"), "reporter-e")
		assertEquals(first.workId, fifth.workId)
		assertEquals(first.workId, repository.findVerifiedAlias("SOURCE", key))
	}

	@Test
	fun `three fresh accounts cannot promote a public alias`() = runTest {
		listOf("fresh-a", "fresh-b", "fresh-c").forEach { reporter(it, settled = false) }
		val resolver = WorkResolver(repository)
		val key = "/fresh/source-key"

		listOf("fresh-a", "fresh-b", "fresh-c").forEach {
			resolver.resolve(fingerprint("SOURCE", key, "Attacker Choice"), it)
		}

		assertEquals(null, repository.findVerifiedAlias("SOURCE", key))
	}

	@Test
	fun `five normal accounts cannot promote a public alias`() = runTest {
		val reporters = listOf("normal-a", "normal-b", "normal-c", "normal-d", "normal-e")
		reporters.forEach(::normalReporter)
		val resolver = WorkResolver(repository)
		val key = "/normal/source-key"

		reporters.forEach {
			resolver.resolve(fingerprint("SOURCE", key, "Attacker Choice"), it)
		}

		assertEquals(null, repository.findVerifiedAlias("SOURCE", key))
	}

	@Test
	fun `one account sees the same exact title as one work across comic sources`() = runTest {
		reporter("reader", settled = false)
		val resolver = WorkResolver(repository)

		val manga = resolver.resolve(
			fingerprint("SOURCE_A", key = "/manga", title = "Shared Story", contentType = "manga"),
			"reader",
		)
		val manhwa = resolver.resolve(
			fingerprint("SOURCE_B", key = "/manhwa", title = "Shared Story", contentType = "manhwa"),
			"reader",
		)

		assertEquals(manga.workId, manhwa.workId)
		assertEquals(ResolutionMethod.OBSERVATION, manhwa.method)
		assertEquals(1L, repository.countWorks())
	}

	@Test
	fun `one account observation cannot select a work for another account`() = runTest {
		reporter("reader-a", settled = false)
		reporter("reader-b", settled = false)
		val resolver = WorkResolver(repository)

		val first = resolver.resolve(fingerprint("SOURCE_A", title = "Untrusted Title"), "reader-a")
		val second = resolver.resolve(fingerprint("SOURCE_B", title = "Untrusted Title"), "reader-b")

		assertNotEquals(first.workId, second.workId)
		assertEquals(2L, repository.countWorks())
	}

	@Test
	fun `one account keeps same-title works with incompatible years separate`() = runTest {
		reporter("reader", settled = false)
		val resolver = WorkResolver(repository)

		val old = resolver.resolve(fingerprint("SOURCE_A", title = "Blue", year = 1990), "reader")
		val remake = resolver.resolve(fingerprint("SOURCE_B", title = "Blue", year = 2025), "reader")

		assertNotEquals(old.workId, remake.workId)
	}

	@Test
	fun `one account keeps a novel separate from a comic with the same title`() = runTest {
		reporter("reader", settled = false)
		val resolver = WorkResolver(repository)

		val comic = resolver.resolve(
			fingerprint("SOURCE_A", title = "Shared Name", contentType = "manhwa"), "reader",
		)
		val novel = resolver.resolve(
			fingerprint("SOURCE_B", title = "Shared Name", contentType = "novel"), "reader",
		)

		assertNotEquals(comic.workId, novel.workId)
	}

	@Test
	fun `a pending observation with the same title but an incompatible year is not reused`() = runTest {
		reporter("reporter-old")
		reporter("reporter-new")
		val resolver = WorkResolver(repository)
		val key = "/shared/reused-title"

		val old = resolver.resolve(fingerprint("SOURCE", key, "Blue", year = 1990), "reporter-old")
		val newer = resolver.resolve(fingerprint("SOURCE", key, "Blue", year = 2025), "reporter-new")

		assertNotEquals(old.workId, newer.workId)
		assertEquals(null, repository.findVerifiedAlias("SOURCE", key))
	}

	@Test
	fun `concurrent public discovery of one alias leaves one work`() {
		reporter("reporter-a")
		reporter("reporter-b")
		val resolver = WorkResolver(repository)
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val results = listOf("reporter-a", "reporter-b").map { userId ->
				executor.submit(Callable {
					start.await()
					runBlocking {
						resolver.resolve(fingerprint("SOURCE", "/same", "Same Work"), userId)
					}
				})
			}
			start.countDown()
			assertEquals(1, results.map { it.get().workId }.distinct().size)
			assertEquals(1L, repository.countWorks())
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `concurrent observations still promote the fifth matching settled report`() {
		val reporters = listOf("reporter-a", "reporter-b", "reporter-c", "reporter-d", "reporter-e")
		reporters.forEach(::reporter)
		val workId = repository.createWork("Concurrent Work", 2018, "manga", false)
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(reporters.size)
		try {
			val reports = reporters.map { userId ->
				executor.submit(Callable {
					start.await()
					repository.observeAlias("SOURCE", "/concurrent", userId, workId, listOf("concurrent work"))
				})
			}
			start.countDown()
			reports.forEach { it.get() }
			assertEquals(workId, repository.findVerifiedAlias("SOURCE", "/concurrent"))
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `trusted linking promotes an existing unverified alias`() {
		val workId = repository.createWork("Trusted Work", 2018, "manga", false)
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"INSERT INTO work_alias (source, source_key, work_id, confidence, evidence) VALUES (?, ?, ?, ?, ?)",
			).use {
				it.setString(1, "SOURCE")
				it.setString(2, "/trusted")
				it.setLong(3, workId)
				it.setDouble(4, 0.25)
				it.setString(5, "legacy")
				it.executeUpdate()
			}
		}

		assertEquals(null, repository.findVerifiedAlias("SOURCE", "/trusted"))
		assertEquals(workId, repository.linkAlias("SOURCE", "/trusted", workId, 1.0, "moderator"))
		assertEquals(workId, repository.findVerifiedAlias("SOURCE", "/trusted"))
	}

	@Test
	fun `concurrent creation of one alias leaves one work`() {
		val executor = Executors.newFixedThreadPool(2)
		try {
			val results = executor.invokeAll(
				List(2) {
					Callable {
						repository.createAndLinkWork(
							canonicalTitle = "Chainsaw Man",
							year = 2018,
							contentType = "manga",
							nsfw = false,
							titles = listOf(TitleToStore("Chainsaw Man", "catalogue")),
							externalIds = mapOf("mal" to "116778"),
							source = "MANGADEX",
							sourceKey = "/title/chainsaw-man",
							confidence = 0.95,
							evidence = "test",
							coverPHash = null,
						)
					}
				},
			)
			assertEquals(1, results.map { it.get().workId }.distinct().size)
			assertEquals(1L, repository.countWorks())
			assertEquals(results.first().get().workId, repository.findVerifiedAlias("MANGADEX", "/title/chainsaw-man"))
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `concurrent catalogue creation across different sources leaves one work`() {
		val executor = Executors.newFixedThreadPool(2)
		try {
			val results = executor.invokeAll(
				listOf("SOURCE_A", "SOURCE_B").map { source ->
					Callable {
						repository.createAndLinkWork(
							canonicalTitle = "Shared Catalogue Work",
							year = 2024,
							contentType = "manhwa",
							nsfw = false,
							titles = listOf(TitleToStore("Shared Catalogue Work", "catalogue")),
							externalIds = mapOf("mangaupdates" to "shared-42"),
							source = source,
							sourceKey = "/title/$source",
							confidence = 0.95,
							evidence = "mangaupdates",
							coverPHash = null,
						)
					}
				},
			)
			assertEquals(1, results.map { it.get().workId }.distinct().size)
			assertEquals(1L, repository.countWorks())
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `concurrent authenticated catalogue creation leaves one work`() {
		listOf("reader-a", "reader-b").forEach { reporter(it, settled = false) }
		val executor = Executors.newFixedThreadPool(2)
		try {
			val results = executor.invokeAll(
				listOf("reader-a", "reader-b").mapIndexed { index, userId ->
					Callable {
						repository.createObservedWork(
							canonicalTitle = "Authenticated Catalogue Work",
							year = 2024,
							contentType = "manhwa",
							nsfw = false,
							titles = listOf(TitleToStore("Authenticated Catalogue Work", "catalogue")),
							externalIds = mapOf("mangaupdates" to "authenticated-42"),
							source = "SOURCE_$index",
							sourceKey = "/title/$index",
							reporterId = userId,
							titleKeys = listOf("authenticated catalogue work"),
							coverPHash = null,
						)
					}
				},
			)
			assertEquals(1, results.map { it.get().workId }.distinct().size)
			assertEquals(1, results.count { it.get().created })
			assertEquals(1L, repository.countWorks())
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `concurrent cross-source creation without catalogue ids leaves one account work`() {
		reporter("reader", settled = false)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val results = executor.invokeAll(
				listOf("SOURCE_A", "SOURCE_B").map { source ->
					Callable {
						repository.createObservedWork(
							canonicalTitle = "No Catalogue Work",
							year = 2024,
							contentType = "manhwa",
							nsfw = false,
							titles = listOf(TitleToStore("No Catalogue Work", "source_observed")),
							externalIds = emptyMap(),
							source = source,
							sourceKey = "/title/$source",
							reporterId = "reader",
							titleKeys = listOf("no catalogue work"),
							coverPHash = null,
						)
					}
				},
			)
			assertEquals(1, results.map { it.get().workId }.distinct().size)
			assertEquals(1, results.count { it.get().created })
			assertEquals(1L, repository.countWorks())
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `conflicting catalogue identifiers do not select an arbitrary work`() {
		val malWork = repository.createWork("MAL Work", 2024, "manga", false)
		val kitsuWork = repository.createWork("Kitsu Work", 2024, "manga", false)
		repository.addExternalIds(malWork, mapOf("mal" to "mal-1"))
		repository.addExternalIds(kitsuWork, mapOf("kitsu" to "kitsu-2"))

		val result = repository.createAndLinkWork(
			canonicalTitle = "Ambiguous Catalogue Work",
			year = 2024,
			contentType = "manga",
			nsfw = false,
			titles = listOf(TitleToStore("Ambiguous Catalogue Work", "catalogue")),
			externalIds = mapOf("mal" to "mal-1", "kitsu" to "kitsu-2"),
			source = "SOURCE",
			sourceKey = "/ambiguous",
			confidence = 0.95,
			evidence = "test",
			coverPHash = null,
		)

		assertTrue(result.created)
		assertNotEquals(malWork, result.workId)
		assertNotEquals(kitsuWork, result.workId)
	}

	@Test
	fun `failed observed creation rolls back the provisional work`() {
		assertFailsWith<SQLException> {
			repository.createObservedWork(
				canonicalTitle = "Rollback Me",
				year = 2026,
				contentType = "manga",
				nsfw = false,
				titles = listOf(TitleToStore("Rollback Me", "source_observed")),
				externalIds = emptyMap(),
				source = "SOURCE",
				sourceKey = "/rollback",
				reporterId = "missing-user",
				titleKeys = listOf("rollback me"),
				coverPHash = null,
			)
		}
		assertEquals(0L, repository.countWorks())
	}

	@Test
	fun `a client supplied external id cannot select an existing work`() = runTest {
		val resolver = resolver("Chainsaw Man")
		val first = resolver.resolve(
			fingerprint("MANGADEX", title = "Chainsaw Man", externalIds = mapOf("mal" to "116778")),
		)
		// No catalogue authority on the second request: its client-controlled id must remain a hint.
		val second = WorkResolver(repository).resolve(
			fingerprint("WEIRD", title = "CSM RAW v2 [Complete]", externalIds = mapOf("mal" to "116778")),
		)

		assertNotEquals(first.workId, second.workId)
		assertNotEquals(ResolutionMethod.EXTERNAL_ID, second.method)
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
	fun `near identical manhwa titles stay separate without corroboration`() = runTest {
		val resolver = resolver()
		val singular = resolver.resolve(
			fingerprint(
				"SOURCE_A", key = "/youngest-son", title = "The Youngest Son of a Conglomerate",
				contentType = "manhwa",
			),
		)
		val plural = resolver.resolve(
			fingerprint(
				"SOURCE_B", key = "/youngest-sons", title = "The Youngest Sons of a Conglomerate",
				contentType = "manhwa",
			),
		)

		assertNotEquals(singular.workId, plural.workId)
		assertEquals(2L, repository.countWorks())
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
		val resolver = resolver()
		resolver.resolve(fingerprint("A", key = "/1", title = "Chainsaw Man"))
		resolver.resolve(fingerprint("C", key = "/3", title = "Chainsaw Man"))
		enricher("Chainsaw Man", "Chainsawman").drainOnce()
		resolver.resolve(fingerprint("B", key = "/2", title = "Chainsawman"))
		enricher("Chainsaw Man", "Chainsawman").drainOnce()

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
	fun `merge and unmerge preserve observations and rating aggregates`() {
		listOf("observer", "rater-a", "rater-b").forEach { reporter(it, settled = false) }
		val ratings = RatingService(RatingRepository(PostgresTestBase.database.source))
		val into = repository.createWork("Same Work", 2024, "manga", false)
		val from = repository.createWork("Same Work", 2024, "manhwa", false)
		repository.observeAlias("SOURCE_B", "/same", "observer", from, listOf("same work"))
		ratings.rate(into, "rater-a", 8)
		ratings.rate(from, "rater-b", 10)

		repository.mergeWorks(from, into, "test")

		assertEquals(listOf(into), repository.observedWorks("observer", listOf("same work")))
		assertEquals(2, ratings.aggregate(into).count)
		assertEquals(9.0, ratings.aggregate(into).mean)

		assertEquals(from to into, repository.unmergeWork(from))
		assertEquals(listOf(from), repository.observedWorks("observer", listOf("same work")))
		assertEquals(1, ratings.aggregate(into).count)
		assertEquals(1, ratings.aggregate(from).count)
	}

	@Test
	fun `linking an unknown pair reports unknown rather than inventing a work`() = runTest {
		assertIs<LinkOutcome.Unknown>(linker.link("A" to "/nope", "B" to "/also-nope", "user_migration"))
		assertEquals(0L, repository.countWorks())
	}
}
