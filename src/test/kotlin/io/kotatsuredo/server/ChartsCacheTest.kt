package io.kotatsuredo.server

import io.kotatsuredo.server.routes.ChartQuery
import io.kotatsuredo.server.routes.ChartWindow
import io.kotatsuredo.server.routes.ChartStore
import io.kotatsuredo.server.routes.Charts
import io.kotatsuredo.server.routes.SimilarWorkDto
import io.kotatsuredo.server.routes.StoredRanking
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

	/** Was "a ranking is rebuilt once it is fifteen minutes old": rebuilding is now the refresh's job alone. */
	@Test
	fun `a request never rebuilds an old ranking, a refresh does`() {
		val cache = cache()

		val first = cache.responseFor(query())
		clock.now = clock.now.plus(Duration.ofMinutes(15))
		val old = cache.responseFor(query())

		assertEquals(1, builds.size)
		assertEquals(first.generatedAt, old.generatedAt)

		cache.refresh(ChartWindow.WEEK)

		assertEquals(2, builds.size)
		assertEquals(clock.millis(), cache.responseFor(query()).generatedAt)
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

	/** Was "a failed rebuild keeps serving the previous ranking": the rebuild that can fail is now the refresh. */
	@Test
	fun `a failed refresh keeps serving the previous ranking and tries again next time`() {
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
		cache.refresh(ChartWindow.WEEK)
		val served = cache.responseFor(query())

		assertEquals(first, served)
		assertEquals(2, calls)

		fail = false
		cache.refresh(ChartWindow.WEEK)
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

	/** Was "while one request rebuilds an expired ranking the others are answered at once": nobody rebuilds now. */
	@Test
	fun `a refresh in progress does not hold up requests, and a request does not start one`() {
		val refreshing = CountDownLatch(1)
		val finishRefresh = CountDownLatch(1)
		val calls = AtomicInteger()
		val cache = Charts(
			build = {
				if (calls.incrementAndGet() > 1) {
					refreshing.countDown()
					finishRefresh.await(5, TimeUnit.SECONDS)
				}
				ranking
			},
			clock = clock,
		)
		val previous = cache.responseFor(query())
		clock.now = clock.now.plus(Duration.ofMinutes(20))

		val pool = Executors.newSingleThreadExecutor()
		try {
			val refresh = pool.submit { cache.refresh(ChartWindow.WEEK) }
			refreshing.await(5, TimeUnit.SECONDS)

			// This thread would hang until the timeout if it were queueing behind the refresh
			assertEquals(previous.generatedAt, cache.responseFor(query()).generatedAt)
			assertEquals(2, calls.get())

			finishRefresh.countDown()
			refresh.get(5, TimeUnit.SECONDS)
			assertEquals(clock.millis(), cache.responseFor(query()).generatedAt)
			assertEquals(2, calls.get())
		} finally {
			finishRefresh.countDown()
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

	private class MemoryStore(var stored: Map<ChartWindow, StoredRanking> = emptyMap()) : ChartStore {
		val saved = mutableListOf<Pair<ChartWindow, StoredRanking>>()
		override fun load() = stored
		override fun save(window: ChartWindow, ranking: StoredRanking) {
			saved += window to ranking
		}
	}

	@Test
	fun `a ranking loaded from the store is served without building`() {
		val storedAt = clock.millis() - 1_000
		val cache = Charts(
			build = { window -> builds += window; ranking },
			clock = clock,
			store = MemoryStore(mapOf(ChartWindow.WEEK to StoredRanking(storedAt, ranking))),
		)

		val answer = cache.responseFor(query())

		assertEquals(emptyList(), builds)
		assertEquals(storedAt, answer.generatedAt)
		assertEquals(4, answer.works.size)
	}

	@Test
	fun `a stored ranking past the ttl is still served, with its real age, and a refresh replaces it`() {
		val storedAt = clock.millis() - Duration.ofDays(3).toMillis()
		val cache = Charts(
			build = { window -> builds += window; ranking },
			backgroundBuild = { window -> builds += window; ranking.take(2) },
			clock = clock,
			store = MemoryStore(mapOf(ChartWindow.WEEK to StoredRanking(storedAt, ranking))),
		)

		val old = cache.responseFor(query())
		assertEquals(emptyList(), builds)
		assertEquals(storedAt, old.generatedAt)
		assertEquals(4, old.works.size)

		cache.refresh(ChartWindow.WEEK)

		val fresh = cache.responseFor(query())
		assertEquals(listOf(ChartWindow.WEEK), builds)
		assertEquals(clock.millis(), fresh.generatedAt)
		assertEquals(2, fresh.works.size)
	}

	@Test
	fun `a refresh builds under the background build and stores what it built`() {
		val store = MemoryStore()
		val cache = Charts(
			build = { error("a request must not build here") },
			backgroundBuild = { window -> builds += window; ranking },
			clock = clock,
			store = store,
		)

		cache.refresh(ChartWindow.MONTH)

		assertEquals(listOf(ChartWindow.MONTH), builds)
		assertEquals(listOf(ChartWindow.MONTH to StoredRanking(clock.millis(), ranking)), store.saved)
	}

	@Test
	fun `a store that throws on read and on write changes nothing else`() {
		val broken = object : ChartStore {
			override fun load(): Map<ChartWindow, StoredRanking> = error("no table")
			override fun save(window: ChartWindow, ranking: StoredRanking) = error("no table")
		}
		val cache = Charts(build = { window -> builds += window; ranking }, clock = clock, store = broken)

		// Nothing was loaded, so the first request builds as it always did
		assertEquals(4, cache.responseFor(query()).works.size)
		assertEquals(listOf(ChartWindow.WEEK), builds)

		// A refresh whose write fails still replaces the ranking in memory
		clock.now = clock.now.plus(Duration.ofMinutes(14))
		cache.refresh(ChartWindow.WEEK)
		assertEquals(clock.millis(), cache.responseFor(query()).generatedAt)
		assertEquals(listOf(ChartWindow.WEEK, ChartWindow.WEEK), builds)
	}

	@Test
	fun `with nothing stored a request still builds the window`() {
		val store = MemoryStore()
		val cache = Charts(build = { window -> builds += window; ranking }, clock = clock, store = store)

		cache.responseFor(query())
		cache.responseFor(query())

		assertEquals(listOf(ChartWindow.WEEK), builds)
		assertEquals(emptyList(), store.saved, "only a refresh stores")
	}

	/**
	 * The one holding the lock may be the background refresh, which is allowed minutes: a request that waited for
	 * it would sit on a request slot the whole time.
	 */
	@Test
	fun `with nothing to serve a request waits only a moment for a build in progress, then is told to come back`() {
		val building = CountDownLatch(1)
		val finish = CountDownLatch(1)
		val cache = Charts(
			build = { error("this request must not build: somebody else is") },
			backgroundBuild = {
				building.countDown()
				finish.await(10, TimeUnit.SECONDS)
				ranking
			},
			clock = clock,
			waitForFirstBuild = Duration.ofMillis(100),
		)
		val pool = Executors.newSingleThreadExecutor()
		try {
			val refresh = pool.submit { cache.refresh(ChartWindow.WEEK) }
			building.await(5, TimeUnit.SECONDS)

			val started = System.nanoTime()
			val refused = assertFailsWith<ApiException> { cache.responseFor(query()) }
			val waitedMillis = (System.nanoTime() - started) / 1_000_000
			val error = refused.error
			assertEquals(true, error is ApiError.Overloaded && error.resource == "charts", "told to come back: $error")
			assertEquals(true, waitedMillis < 5_000, "waited $waitedMillis ms, far short of the build")

			// Once the build is done the same request is answered from it
			finish.countDown()
			refresh.get(5, TimeUnit.SECONDS)
			assertEquals(4, cache.responseFor(query()).works.size)
		} finally {
			finish.countDown()
			pool.shutdownNow()
		}
	}

	/** Skipping the window, as a refresh does when a ranking is being served, would leave it empty for a quarter of an hour. */
	@Test
	fun `with nothing to serve a refresh waits for a request's attempt and then builds and stores`() {
		val requestBuilding = CountDownLatch(1)
		val failRequest = CountDownLatch(1)
		val store = MemoryStore()
		val cache = Charts(
			build = {
				requestBuilding.countDown()
				failRequest.await(10, TimeUnit.SECONDS)
				error("timed out, as a request's build does on a busy start")
			},
			backgroundBuild = { window -> builds += window; ranking },
			clock = clock,
			store = store,
		)
		val pool = Executors.newFixedThreadPool(2)
		try {
			val request = pool.submit { runCatching { cache.responseFor(query()) } }
			requestBuilding.await(5, TimeUnit.SECONDS)
			val refresh = pool.submit { cache.refresh(ChartWindow.WEEK) }

			// The refresh is waiting behind the request, not gone
			Thread.sleep(200)
			assertEquals(false, refresh.isDone)
			assertEquals(emptyList(), builds)

			failRequest.countDown()
			request.get(5, TimeUnit.SECONDS)
			refresh.get(5, TimeUnit.SECONDS)
			assertEquals(listOf(ChartWindow.WEEK), builds)
			assertEquals(listOf(ChartWindow.WEEK to StoredRanking(clock.millis(), ranking)), store.saved)
			assertEquals(4, cache.responseFor(query()).works.size)
		} finally {
			failRequest.countDown()
			pool.shutdownNow()
		}
	}
}
