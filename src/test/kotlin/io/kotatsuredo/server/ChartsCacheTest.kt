package io.kotatsuredo.server

import io.kotatsuredo.server.routes.ChartQuery
import io.kotatsuredo.server.routes.ChartWindow
import io.kotatsuredo.server.routes.Charts
import io.kotatsuredo.server.routes.SimilarWorkDto
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The aggregate is the expensive part and does not depend on the filters, so it runs once per window. */
class ChartsCacheTest {

	private class MutableClock(var now: Instant = Instant.parse("2026-10-16T12:00:00Z")) : Clock() {
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(zone: java.time.ZoneId?) = this
		override fun instant(): Instant = now
	}

	private val builds = mutableListOf<ChartWindow>()
	private val clock = MutableClock()
	private val ranking = listOf(
		work("1", "manhwa", "romance"),
		work("2", "MANGA", "action"),
		work("3", "manhwa", "action"),
		work("4", null, "romance"),
	)

	private fun work(id: String, type: String?, vararg genres: String) = SimilarWorkDto(
		workId = id,
		title = "Work $id",
		year = null,
		contentType = type,
		nsfw = false,
		genres = genres.toList(),
		sharedGenres = 0,
		score = 1.0,
		sources = emptyList(),
	)

	private fun cache() = Charts(
		build = { window -> builds += window; ranking },
		clock = clock,
	)

	private fun query(
		window: ChartWindow = ChartWindow.WEEK,
		type: String? = null,
		genre: String? = null,
		limit: Int = 30,
	) = ChartQuery(window, type, genre, limit)

	private fun Charts.ids(query: ChartQuery) = responseFor(query).works.map { it.workId }

	@Test
	fun `a ranking is built once while it is fresh, and says when it was built`() {
		val cache = cache()

		val first = cache.responseFor(query())
		clock.now = clock.now.plus(Duration.ofMinutes(14))
		val second = cache.responseFor(query())

		assertEquals(listOf(ChartWindow.WEEK), builds)
		assertEquals(first.generatedAt, second.generatedAt)
		assertEquals(Instant.parse("2026-10-16T12:00:00Z").toEpochMilli(), second.generatedAt)
	}

	@Test
	fun `a ranking is rebuilt once it is fifteen minutes old`() {
		val cache = cache()

		cache.responseFor(query())
		clock.now = clock.now.plus(Duration.ofMinutes(15))
		val rebuilt = cache.responseFor(query())

		assertEquals(2, builds.size)
		assertEquals(clock.millis(), rebuilt.generatedAt)
	}

	/** What a cache keyed on the whole query could not promise: walking the filters costs nothing. */
	@Test
	fun `cycling through every filter still builds each window only once`() {
		val cache = cache()

		repeat(3) {
			for (window in ChartWindow.entries) {
				for (type in listOf(null, "manga", "manhwa", "manhua")) {
					for (genre in listOf(null, "romance", "action", "comedy")) {
						for (limit in 1..50) {
							cache.responseFor(query(window, type, genre, limit))
						}
					}
				}
			}
		}

		assertEquals(ChartWindow.entries.toList(), builds)
	}

	@Test
	fun `filters are applied to the ranking in order`() {
		val cache = cache()

		assertEquals(listOf("1", "2", "3", "4"), cache.ids(query()))
		assertEquals(listOf("1", "3"), cache.ids(query(type = "manhwa")))
		assertEquals(listOf("2"), cache.ids(query(type = "manga")), "an older client's upper-case type still counts")
		assertEquals(listOf("1", "4"), cache.ids(query(genre = "romance")))
		assertEquals(listOf("3"), cache.ids(query(type = "manhwa", genre = "action")))
		assertEquals(listOf("1", "2"), cache.ids(query(limit = 2)))
		assertEquals(emptyList(), cache.ids(query(genre = "comedy")))
	}

	@Test
	fun `a failed rebuild keeps serving the previous ranking and tries again next time`() {
		var fail = false
		var calls = 0
		val cache = Charts(
			build = {
				calls++
				if (fail) error("database is away")
				ranking
			},
			clock = clock,
		)
		val first = cache.responseFor(query())

		fail = true
		clock.now = clock.now.plus(Duration.ofMinutes(20))
		val served = cache.responseFor(query())

		assertEquals(first, served)
		assertEquals(2, calls)

		fail = false
		assertEquals(clock.millis(), cache.responseFor(query()).generatedAt)
		assertEquals(3, calls)
	}

	/** What makes a request instant: it finds the work already done, and only asks if it was not. */
	@Test
	fun `a background refresh means a request never has to build`() {
		val cache = cache()

		cache.refresh(ChartWindow.WEEK)
		clock.now = clock.now.plus(Duration.ofMinutes(14))
		cache.refresh(ChartWindow.WEEK)
		clock.now = clock.now.plus(Duration.ofMinutes(14))
		val answer = cache.responseFor(query())

		assertEquals(listOf(ChartWindow.WEEK, ChartWindow.WEEK), builds)
		assertEquals(4, answer.works.size)
	}

	@Test
	fun `a failed background refresh leaves the ranking there`() {
		var fail = false
		val cache = Charts(
			build = {
				if (fail) error("database is away")
				ranking
			},
			clock = clock,
		)
		cache.refresh(ChartWindow.WEEK)

		fail = true
		cache.refresh(ChartWindow.WEEK)

		assertEquals(4, cache.responseFor(query()).works.size)
	}

	@Test
	fun `with nothing to serve a failed build is an error`() {
		val cache = Charts(build = { error("database is away") }, clock = clock)

		assertFailsWith<IllegalStateException> { cache.responseFor(query()) }
	}

	@Test
	fun `while one request rebuilds an expired ranking the others are answered at once`() {
		val rebuilding = CountDownLatch(1)
		val finishRebuild = CountDownLatch(1)
		val calls = AtomicInteger()
		val cache = Charts(
			build = {
				if (calls.incrementAndGet() > 1) {
					rebuilding.countDown()
					finishRebuild.await(5, TimeUnit.SECONDS)
				}
				ranking
			},
			clock = clock,
		)
		val previous = cache.responseFor(query())
		clock.now = clock.now.plus(Duration.ofMinutes(20))

		val pool = Executors.newSingleThreadExecutor()
		try {
			val rebuild = pool.submit<Long> { cache.responseFor(query()).generatedAt }
			rebuilding.await(5, TimeUnit.SECONDS)

			// This thread would hang until the timeout if it were queueing behind the rebuild
			assertEquals(previous.generatedAt, cache.responseFor(query()).generatedAt)
			assertEquals(2, calls.get())

			finishRebuild.countDown()
			assertEquals(clock.millis(), rebuild.get(5, TimeUnit.SECONDS))
		} finally {
			finishRebuild.countDown()
			pool.shutdownNow()
		}
	}

	@Test
	fun `first requests for a window wait for one build rather than each running it`() {
		val calls = AtomicInteger()
		val start = CountDownLatch(1)
		val cache = Charts(
			build = {
				calls.incrementAndGet()
				Thread.sleep(100)
				ranking
			},
			clock = clock,
		)
		val pool = Executors.newFixedThreadPool(8)
		try {
			val answers = List(8) {
				pool.submit<List<SimilarWorkDto>> {
					start.await()
					cache.responseFor(query()).works
				}
			}
			start.countDown()
			answers.forEach { assertEquals(ranking, it.get(5, TimeUnit.SECONDS)) }
			assertEquals(1, calls.get())
		} finally {
			pool.shutdownNow()
		}
	}
}
