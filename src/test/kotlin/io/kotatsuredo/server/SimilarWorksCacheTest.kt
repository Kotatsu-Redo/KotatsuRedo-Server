package io.kotatsuredo.server

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

	private fun cache(maxEntries: Int = 100) = SimilarWorks(
		build = { workId -> builds += workId; SimilarWorksResponse(workId.toString(), emptyList()) },
		clock = clock,
		ttl = Duration.ofHours(1),
		maxEntries = maxEntries,
	)

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

		assertEquals(listOf(7L, 7L), builds)
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
