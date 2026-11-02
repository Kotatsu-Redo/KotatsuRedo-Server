package io.kotatsuredo.server

import io.kotatsuredo.server.routes.SimilarWorkDto
import io.kotatsuredo.server.routes.SimilarWorks
import io.kotatsuredo.server.routes.SimilarWorksResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** The similar-works answer is expensive and the same for everyone, so it is kept - briefly, and bounded. */
class SimilarWorksCacheTest {

	private class MutableClock(var now: Instant = Instant.parse("2026-10-16T12:00:00Z")) : Clock() {
		override fun getZone() = ZoneOffset.UTC
		override fun withZone(zone: java.time.ZoneId?) = this
		override fun instant(): Instant = now
	}

	private val builds = mutableListOf<Long>()
	private val clock = MutableClock()

	/** What was asked to happen behind a request, until a test lets it. */
	private val behind = ArrayDeque<Runnable>()

	private fun cache(maxEntries: Int = 100) = SimilarWorks(
		build = { workId ->
			builds += workId
			failure?.let { throw it }
			SimilarWorksResponse(workId.toString(), List(generation) { similar(it) })
		},
		clock = clock,
		ttl = Duration.ofHours(1),
		maxEntries = maxEntries,
		maxStaleness = Duration.ofDays(1),
		background = { behind += it },
	)

	private var generation = 0
	private var failure: Exception? = null

	private fun similar(id: Int) = SimilarWorkDto(
		workId = id.toString(),
		title = "Work",
		year = null,
		contentType = null,
		nsfw = false,
		genres = emptyList(),
		sharedGenres = 0,
		score = 0.0,
		sources = emptyList(),
	)

	private fun runBehind() {
		while (behind.isNotEmpty()) behind.removeFirst().run()
	}

	@Test
	fun `an answer is built once while it is fresh`() {
		val cache = cache()

		repeat(5) { cache.responseFor(7) }

		assertEquals(listOf(7L), builds)
	}

	@Test
	fun `an answer is rebuilt once it is an hour old`() {
		val cache = cache()

		cache.responseFor(7)
		clock.now = clock.now.plus(Duration.ofMinutes(59))
		cache.responseFor(7)
		clock.now = clock.now.plus(Duration.ofMinutes(2))
		cache.responseFor(7)
		runBehind()

		assertEquals(listOf(7L, 7L), builds)
	}

	@Test
	fun `nobody waits for an old answer to be rebuilt`() {
		val cache = cache()
		cache.responseFor(7)
		clock.now = clock.now.plus(Duration.ofHours(2))
		generation = 1

		assertEquals(0, cache.responseFor(7).similar.size)
		assertEquals(listOf(7L), builds)

		runBehind()
		assertEquals(1, cache.responseFor(7).similar.size)
		assertEquals(listOf(7L, 7L), builds)
	}

	@Test
	fun `an old answer is only rebuilt once however many ask`() {
		val cache = cache()
		cache.responseFor(7)
		clock.now = clock.now.plus(Duration.ofHours(2))

		repeat(5) { cache.responseFor(7) }
		assertEquals(1, behind.size)
		runBehind()

		assertEquals(listOf(7L, 7L), builds)
	}

	@Test
	fun `a failed rebuild keeps the old answer and tries again`() {
		val cache = cache()
		cache.responseFor(7)
		clock.now = clock.now.plus(Duration.ofHours(2))
		failure = IllegalStateException("database is away")

		cache.responseFor(7)
		runBehind()
		assertEquals(0, cache.responseFor(7).similar.size)

		failure = null
		generation = 1
		runBehind()
		assertEquals(1, cache.responseFor(7).similar.size)
	}

	@Test
	fun `an answer far too old is waited for`() {
		val cache = cache()
		cache.responseFor(7)
		clock.now = clock.now.plus(Duration.ofDays(3))
		generation = 1

		assertEquals(1, cache.responseFor(7).similar.size)
		assertEquals(0, behind.size)
	}

	@Test
	fun `the least recently used answer goes first once it is full`() {
		val cache = cache(maxEntries = 2)

		cache.responseFor(1)
		cache.responseFor(2)
		cache.responseFor(1) // 2 is now the least recently used
		cache.responseFor(3) // evicts 2
		cache.responseFor(1)
		cache.responseFor(2)

		assertEquals(listOf(1L, 2L, 3L, 2L), builds)
	}
}
