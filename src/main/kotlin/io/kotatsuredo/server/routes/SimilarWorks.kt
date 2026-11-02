package io.kotatsuredo.server.routes

import io.kotatsuredo.server.works.SimilarWork
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The "works like this one" answer, built once per work and kept briefly.
 *
 * One answer is a similarity query with its signals, then one batched genre and one batched source
 * lookup for all candidates, and it is asked for by every app with suggestions on, which is now the
 * default, on every manga page and every suggestions run. The answer depends only on the work, never on who asks, so
 * the authenticated and the anonymous route share it.
 *
 * An hour is short beside how fast it changes: genres move when a catalogue answers or a quorum
 * lands, coverage when somebody opens the work somewhere new, both on a scale of days. For the same
 * reason nobody waits for an answer past that hour: they get it as it was, and it is rebuilt behind
 * them. Bounded and least-recently-used, so a sweep over every work cannot grow it without limit.
 */
class SimilarWorks(
	private val build: (Long) -> SimilarWorksResponse,
	private val clock: Clock = Clock.systemUTC(),
	private val ttl: Duration = DEFAULT_TTL,
	private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
	private val maxStaleness: Duration = DEFAULT_MAX_STALENESS,
	private val background: Executor = Dispatchers.IO.limitedParallelism(REFRESH_PARALLELISM).asExecutor(),
) {

	constructor(works: WorkRepository) : this(build = { workId -> buildResponse(works, workId) })

	private class Entry(val response: SimilarWorksResponse, val expiresAtMillis: Long)

	private val entries = object : LinkedHashMap<Long, Entry>(INITIAL_CAPACITY, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>): Boolean = size > maxEntries
	}

	private val refreshing = ConcurrentHashMap.newKeySet<Long>()

	fun responseFor(workId: Long): SimilarWorksResponse {
		val now = clock.millis()
		val known = synchronized(entries) { entries[workId] }
		if (known != null) {
			if (known.expiresAtMillis > now) return known.response
			if (known.expiresAtMillis + maxStaleness.toMillis() > now) {
				refreshBehind(workId)
				return known.response
			}
		}
		// Built outside the lock: two concurrent misses for one work build it twice, which is cheaper
		// than every other work waiting behind a slow one.
		return store(workId, build(workId), now)
	}

	/** One rebuild at a time per work. A failed one leaves the old answer, and the next request tries again. */
	private fun refreshBehind(workId: Long) {
		if (!refreshing.add(workId)) return
		try {
			background.execute {
				try {
					store(workId, build(workId), clock.millis())
				} catch (e: Exception) {
					logger.warn("Could not refresh what is similar to {}", workId, e)
				} finally {
					refreshing.remove(workId)
				}
			}
		} catch (e: RejectedExecutionException) {
			refreshing.remove(workId)
			logger.warn("Could not start refreshing what is similar to {}", workId, e)
		}
	}

	private fun store(workId: Long, response: SimilarWorksResponse, now: Long): SimilarWorksResponse {
		synchronized(entries) {
			entries[workId] = Entry(response, now + ttl.toMillis())
		}
		return response
	}

	companion object {

		private val logger = LoggerFactory.getLogger(SimilarWorks::class.java)

		val DEFAULT_TTL: Duration = Duration.ofHours(1)

		/** How long past that an answer is still given while a new one is made. Past this it is waited for. */
		val DEFAULT_MAX_STALENESS: Duration = Duration.ofDays(1)

		/**
		 * Rebuilds running at once. An evening's worth of answers can go stale together, and each one holds a
		 * connection from a pool of ten that readers' requests are waiting on with a five-second patience.
		 */
		private const val REFRESH_PARALLELISM = 2

		/** A few KB each, so this is a few tens of MB at the very most. */
		const val DEFAULT_MAX_ENTRIES = 5_000

		private const val INITIAL_CAPACITY = 256

		/** Enough for the app to pick one the reader has enabled, without listing every mirror. */
		internal const val MAX_SOURCES_PER_SIMILAR = 5

		/** Three queries for the candidates' details, whatever their number: genres and sources batched. */
		private fun buildResponse(works: WorkRepository, workId: Long): SimilarWorksResponse {
			val candidates = works.similarTo(workId)
			val ids = candidates.map { it.workId }
			val genres = works.genresOfAll(ids)
			val sources = works.sourcesForAll(ids, limit = MAX_SOURCES_PER_SIMILAR)
			return SimilarWorksResponse(
				workId = workId.toString(),
				similar = candidates
					// Co-reading can bring in a work nobody has corroborated a source for, which no
					// reader could open: worth nothing to list.
					.filter { !it.coReadOnly || !sources[it.workId].isNullOrEmpty() }
					.map { candidate ->
						candidate.toDto(genres[candidate.workId].orEmpty(), sources[candidate.workId].orEmpty())
					},
			)
		}
	}
}

/** One work as the app parses it, shared by every answer that lists works to open. */
internal fun SimilarWork.toDto(genres: List<String>, sources: List<WorkSource>) = SimilarWorkDto(
	workId = workId.toString(),
	title = title,
	year = year,
	contentType = contentType,
	nsfw = nsfw,
	genres = genres,
	sharedGenres = sharedGenres,
	score = score,
	sources = sources.map {
		WorkSourceDto(
			source = it.source,
			key = it.sourceKey,
			reporters = it.reporters,
			lastSeen = it.lastSeen.toString(),
			verified = it.verified,
			coverUrl = it.coverUrl,
		)
	},
)
