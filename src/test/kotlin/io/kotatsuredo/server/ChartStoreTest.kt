package io.kotatsuredo.server

import io.kotatsuredo.server.routes.ChartQuery
import io.kotatsuredo.server.routes.ChartWindow
import io.kotatsuredo.server.routes.Charts
import io.kotatsuredo.server.routes.DatabaseChartStore
import io.kotatsuredo.server.routes.SimilarWorkDto
import io.kotatsuredo.server.routes.StoredRanking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rankings survive a restart: what one `Charts` stored, the next one serves at once. */
class ChartStoreTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val store by lazy { DatabaseChartStore(source) }

	@BeforeTest
	@AfterTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		// Left behind, a stored ranking would be served by every other test that starts the application
		source.connection.use { it.createStatement().use { s -> s.execute("TRUNCATE chart_ranking") } }
	}

	private val ranking = listOf(
		SimilarWorkDto(
			workId = "7",
			title = "Seven \"quoted\" é",
			year = 2021,
			contentType = "manhwa",
			nsfw = false,
			genres = listOf("romance"),
			sharedGenres = 0,
			score = 2.5,
			sources = emptyList(),
		),
	)

	private fun query() = ChartQuery(ChartWindow.WEEK, null, null, 30)

	@Test
	fun `a refresh stores its ranking and a second instance reads it back`() {
		val first = Charts(build = { error("no request builds here") }, backgroundBuild = { ranking }, store = store)
		first.refresh(ChartWindow.WEEK)
		val written = first.responseFor(query())

		val second = Charts(build = { error("a stored ranking must not need a build") }, store = store)
		val answer = second.responseFor(query())

		assertEquals(ranking, answer.works)
		assertEquals(written.generatedAt, answer.generatedAt)
	}

	@Test
	fun `a second refresh replaces the row of its window`() {
		store.save(ChartWindow.WEEK, StoredRanking(1_000, ranking))
		store.save(ChartWindow.WEEK, StoredRanking(2_000, emptyList()))
		store.save(ChartWindow.DAY, StoredRanking(3_000, ranking))

		val loaded = store.load()

		assertEquals(StoredRanking(2_000, emptyList()), loaded.getValue(ChartWindow.WEEK))
		assertEquals(StoredRanking(3_000, ranking), loaded.getValue(ChartWindow.DAY))
		assertEquals(2, loaded.size)
	}
}
