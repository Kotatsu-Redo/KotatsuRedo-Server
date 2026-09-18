package io.kotatsuredo.server

import io.kotatsuredo.server.catalogue.CatalogueLookup
import io.kotatsuredo.server.catalogue.CatalogueProvider
import io.kotatsuredo.server.catalogue.CatalogueRecord
import io.kotatsuredo.server.catalogue.CatalogueUnavailable
import io.kotatsuredo.server.works.TitleNormalizer
import io.kotatsuredo.server.works.WorkEnricher
import io.kotatsuredo.server.works.WorkRepository
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Enrichment is what used to happen inside the request, and these are the properties that moving it
 * off the hot path must not cost: the catalogue's renderings still arrive, its identifiers still
 * collapse duplicates, and a provider having a bad minute still costs nothing permanent.
 */
class WorkEnricherTest {

	private val repository by lazy { WorkRepository(PostgresTestBase.database.source) }
	private val calls = AtomicInteger()

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		calls.set(0)
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE work, work_title, work_alias, work_cover_hash, work_external_id, " +
						"work_relation, work_merge_log, work_enrichment, seed_progress CASCADE",
				)
			}
		}
	}

	private fun catalogue(record: (String) -> CatalogueRecord?) = CatalogueLookup(
		listOf(
			object : CatalogueProvider {
				override val name = "fake"
				override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord? {
					calls.incrementAndGet()
					return record(title)
				}
			},
		),
	)

	private fun record(
		title: String,
		titles: List<String> = listOf(title),
		year: Int? = 2018,
		ids: Map<String, String> = emptyMap(),
	) = CatalogueRecord(
		provider = "fake",
		externalId = "1",
		canonicalTitle = title,
		titles = titles,
		year = year,
		contentType = "manga",
		nsfw = false,
		externalIds = ids,
	)

	private fun queued(title: String, year: Int? = null): Long {
		val id = repository.createWork(title, year, "manga", false)
		repository.enqueueEnrichment(id, title, year, "manga")
		return id
	}

	private fun queueSize(): Int = PostgresTestBase.database.source.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery("SELECT count(*) FROM work_enrichment WHERE done_at IS NULL")
				.use { it.next(); it.getInt(1) }
		}
	}

	@Test
	fun `the catalogue's renderings reach the work it was queued for`() = runTest {
		val id = queued("Chainsaw Man")

		val report = WorkEnricher(
			repository,
			catalogue { record("Chainsaw Man", titles = listOf("Chainsaw Man", "Chainsawman", "チェンソーマン")) },
		).drainOnce()

		assertEquals(1, report.enriched)
		val titles = repository.titlesOf(id)
		listOf("Chainsawman", "チェンソーマン").forEach { rendering ->
			assertTrue(
				TitleNormalizer.keys(rendering).any { it in titles },
				"'$rendering' should be indexed after enrichment: $titles",
			)
		}
		assertEquals(0, queueSize(), "a finished entry must leave the queue")
	}

	/**
	 * The case that makes deferring the lookup safe: two sources each created their own work before
	 * anything knew better, and the catalogue's identifier is what collapses them.
	 */
	@Test
	fun `a work whose catalogue id belongs to an existing work is merged into it`() = runTest {
		val original = repository.createWork("Chainsaw Man", 2018, "manga", false)
		repository.addExternalIds(original, mapOf("mal" to "116778"))
		val provisional = queued("Chainsawman")

		val report = WorkEnricher(
			repository,
			catalogue { record("Chainsaw Man", ids = mapOf("mal" to "116778")) },
		).drainOnce()

		assertEquals(1, report.merged)
		assertEquals(original, repository.mergedInto(provisional), "the provisional work should redirect")
		assertEquals(0, queueSize())
	}

	@Test
	fun `a provider that cannot answer keeps the work queued for another try`() = runTest {
		val id = queued("Briefly Unreachable")

		val report = WorkEnricher(
			repository,
			CatalogueLookup(
				listOf(
					object : CatalogueProvider {
						override val name = "down"
						override suspend fun lookup(title: String, year: Int?, contentType: String?): CatalogueRecord =
							throw CatalogueUnavailable(name)
					},
				),
			),
		).drainOnce()

		assertEquals(1, report.deferred)
		assertEquals(1, queueSize(), "an outage must not drop the work from the queue")
		assertTrue(repository.dueEnrichments(10).isEmpty(), "it should be waiting, not retried at once")
		assertNull(repository.metadataOf(id)?.second, "nothing should have been written")
	}

	/**
	 * The works that predate any catalogue answering are the ones nobody else's source can match, so
	 * seeding them is not a one-off cleanup: the enricher keeps feeding itself until there are none
	 * left, at whatever pace it can actually manage.
	 */
	@Test
	fun `works nothing has ever described are queued without anyone asking`() = runTest {
		repeat(3) { repository.createWork("Never Described $it", 2020, "manga", false) }

		val report = WorkEnricher(repository, catalogue { record(it) }).drainOnce()

		assertEquals(3, report.seeded, "the backfill should have queued them itself")
		assertEquals(0, repository.backfillCandidates(), "nothing should be left undescribed")
	}

	/** Seeding waits for the queue to drain, so the pace stays whatever the enricher can manage. */
	@Test
	fun `seeding is skipped while there is still a queue to work through`() = runTest {
		repeat(3) { repository.createWork("Never Described $it", 2020, "manga", false) }
		repeat(25) { queued("Queued $it") }

		val report = WorkEnricher(repository, catalogue { null }, batchSize = 1).drainOnce()

		assertEquals(0, report.seeded)
		assertEquals(3, repository.backfillCandidates(), "they wait their turn")
	}

	@Test
	fun `a work no catalogue lists keeps its own title and leaves the queue`() = runTest {
		val id = queued("Some Untracked Doujin")

		val report = WorkEnricher(repository, catalogue { null }).drainOnce()

		assertEquals(1, report.unlisted)
		assertEquals(0, queueSize())
		assertEquals("Some Untracked Doujin", repository.metadataOf(id)?.first)
	}

	/** A provider's malformed year must never reach a SMALLINT column, nor erase the source's own. */
	@Test
	fun `an impossible catalogue year is ignored`() = runTest {
		val id = queued("Fallback Year", year = 2026)

		WorkEnricher(repository, catalogue { record("Fallback Year", year = Int.MAX_VALUE) }).drainOnce()

		assertEquals(2026, repository.metadataOf(id)?.second)
	}
}
