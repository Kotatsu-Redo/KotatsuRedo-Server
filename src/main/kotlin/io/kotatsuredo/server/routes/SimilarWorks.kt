package io.kotatsuredo.server.routes

import io.kotatsuredo.server.works.WorkRepository
import java.time.Clock
import java.time.Duration

/**
 * The "works like this one" answer, built once per work and kept briefly.
 *
 * One answer is a similarity query plus a genre and a source lookup per candidate - about forty
 * queries - and it is asked for by every app with suggestions on, which is now the default, on every
 * manga page and every suggestions run. The answer depends only on the work, never on who asks, so
 * the authenticated and the anonymous route share it.
 *
 * An hour is short beside how fast it changes: genres move when a catalogue answers or a quorum
 * lands, coverage when somebody opens the work somewhere new, both on a scale of days. Bounded and
 * least-recently-used, so a sweep over every work cannot grow it without limit.
 */
class SimilarWorks(
	private val build: (Long) -> SimilarWorksResponse,
	private val clock: Clock = Clock.systemUTC(),
	private val ttl: Duration = DEFAULT_TTL,
	private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

	constructor(works: WorkRepository) : this(build = { workId -> buildResponse(works, workId) })

	private class Entry(val response: SimilarWorksResponse, val expiresAtMillis: Long)

	private val entries = object : LinkedHashMap<Long, Entry>(INITIAL_CAPACITY, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>): Boolean = size > maxEntries
	}

	fun responseFor(workId: Long): SimilarWorksResponse {
		val now = clock.millis()
		synchronized(entries) {
			entries[workId]?.let { entry ->
				if (entry.expiresAtMillis > now) return entry.response
				entries.remove(workId)
			}
		}
		// Built outside the lock: two concurrent misses for one work build it twice, which is cheaper
		// than every other work waiting behind a slow one.
		val response = build(workId)
		synchronized(entries) {
			entries[workId] = Entry(response, now + ttl.toMillis())
		}
		return response
	}

	companion object {

		val DEFAULT_TTL: Duration = Duration.ofHours(1)

		/** A few KB each, so this is a few tens of MB at the very most. */
		const val DEFAULT_MAX_ENTRIES = 5_000

		private const val INITIAL_CAPACITY = 256

		/** Enough for the app to pick one the reader has enabled, without listing every mirror. */
		private const val MAX_SOURCES_PER_SIMILAR = 5

		private fun buildResponse(works: WorkRepository, workId: Long) = SimilarWorksResponse(
			workId = workId.toString(),
			similar = works.similarTo(workId).map { candidate ->
				SimilarWorkDto(
					workId = candidate.workId.toString(),
					title = candidate.title,
					year = candidate.year,
					contentType = candidate.contentType,
					nsfw = candidate.nsfw,
					genres = works.genresOf(candidate.workId),
					sharedGenres = candidate.sharedGenres,
					score = candidate.score,
					sources = works.sourcesFor(candidate.workId, limit = MAX_SOURCES_PER_SIMILAR).map {
						WorkSourceDto(
							source = it.source,
							key = it.sourceKey,
							reporters = it.reporters,
							lastSeen = it.lastSeen.toString(),
							verified = it.verified,
						)
					},
				)
			},
		)
	}
}
