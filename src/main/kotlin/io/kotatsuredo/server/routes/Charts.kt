package io.kotatsuredo.server.routes

import io.kotatsuredo.server.works.SimilarWork
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How far back a chart looks. Rolling, so "today" is not empty just after midnight in Paris. */
enum class ChartWindow(val wire: String, val hours: Int) {
	DAY("day", 24),
	WEEK("week", 24 * 7),
	MONTH("month", 24 * 30),
	;

	companion object {
		fun parse(raw: String?): ChartWindow? = entries.firstOrNull { it.wire == raw }
	}
}

/** Everything an answer depends on. [genre] is already canonical, so aliases give the same answer. */
data class ChartQuery(
	val window: ChartWindow,
	val type: String?,
	val genre: String?,
	val limit: Int,
)

@Serializable
data class ChartsResponse(
	val window: String,
	@SerialName("generated_at") val generatedAt: Long,
	val works: List<SimilarWorkDto>,
)

/**
 * The "top today, this week, this month" answer.
 *
 * The aggregate behind it is the one expensive thing here, and it does not depend on the filters. So it
 * runs once per window however many filters are asked for, at most three times in [ttl], and every answer
 * is a cheap in-memory filter of the result. Cycling through filter combinations therefore costs the
 * database nothing, which a cache keyed on the whole query could not promise.
 *
 * [schedule] keeps the rankings fresh from the background, so requests normally find one ready. Should a
 * window expire anyway, one request rebuilds it and everyone else keeps being answered from the previous
 * ranking rather than queueing behind it. If that rebuild fails, the previous ranking also goes on being
 * served: a slightly old chart beats an error. Only requests in the first moments after a start can wait.
 * `generated_at` says how old an answer is.
 */
class Charts(
	private val build: (ChartWindow) -> List<SimilarWorkDto>,
	private val clock: Clock = Clock.systemUTC(),
	private val ttl: Duration = DEFAULT_TTL,
) {

	constructor(works: WorkRepository) : this(build = { window -> buildWorks(works, window) })

	private class Ranking(val generatedAt: Long, val works: List<SimilarWorkDto>)

	private class Slot {
		val lock = ReentrantLock()

		@Volatile
		var ranking: Ranking? = null
	}

	private val slots = ChartWindow.entries.associateWith { Slot() }

	fun responseFor(query: ChartQuery): ChartsResponse {
		val ranking = rankingFor(query.window)
		val works = ranking.works.asSequence()
			.filter { query.type == null || it.contentType?.lowercase() == query.type }
			.filter { query.genre == null || query.genre in it.genres }
			.take(query.limit)
			.toList()
		return ChartsResponse(query.window.wire, ranking.generatedAt, works)
	}

	/**
	 * Keeps every ranking fresh from the background, so that once the server has been up for a moment, no
	 * reader's request ever runs the aggregate. Costs the database three queries each [ttl], with or without
	 * anyone asking, which on the sizes measured for this route is well under a second of work.
	 */
	fun schedule(scope: CoroutineScope, initialDelay: Duration = WARM_UP_DELAY) = scope.launch(Dispatchers.IO) {
		delay(initialDelay.toMillis())
		while (isActive) {
			ChartWindow.entries.forEach(::refresh)
			delay(ttl.minus(REFRESH_MARGIN).toMillis())
		}
	}

	/** Rebuilds a window now. A failure leaves what is there, and a rebuild already running is enough. */
	fun refresh(window: ChartWindow) {
		val slot = slots.getValue(window)
		if (!slot.lock.tryLock()) {
			return
		}
		try {
			slot.ranking = Ranking(clock.millis(), build(window))
		} catch (e: Exception) {
			logger.warn("Could not refresh the {} chart", window.wire, e)
		} finally {
			slot.lock.unlock()
		}
	}

	/** For a genre no chart can carry: nothing to ask or keep. */
	fun emptyFor(window: ChartWindow) = ChartsResponse(window.wire, clock.millis(), emptyList())

	private fun rankingFor(window: ChartWindow): Ranking {
		val slot = slots.getValue(window)
		val previous = slot.ranking
		if (previous != null && previous.isFresh()) {
			return previous
		}
		if (!slot.lock.tryLock()) {
			// Someone is already rebuilding this one. With an answer to give, there is no need to wait.
			if (previous != null) return previous
			slot.lock.lock()
		}
		try {
			slot.ranking?.takeIf { it.isFresh() }?.let { return it }
			return try {
				Ranking(clock.millis(), build(window)).also { slot.ranking = it }
			} catch (e: Exception) {
				if (previous == null) throw e
				logger.warn("Could not rebuild the {} chart, still serving the previous one", window.wire, e)
				previous
			}
		} finally {
			slot.lock.unlock()
		}
	}

	private fun Ranking.isFresh() = clock.millis() - generatedAt < ttl.toMillis()

	companion object {

		private val logger = LoggerFactory.getLogger(Charts::class.java)

		val DEFAULT_TTL: Duration = Duration.ofMinutes(15)

		/** Long enough for a starting server to finish what is more urgent. */
		private val WARM_UP_DELAY: Duration = Duration.ofSeconds(30)

		/** A refresh finishes this long before the ranking would expire and a request would have to do it. */
		private val REFRESH_MARGIN: Duration = Duration.ofMinutes(1)

		/** Genres no chart lists, so asking for one is an empty chart without touching anything. */
		val EXCLUDED_GENRES = setOf("adult", "ecchi")

		/**
		 * The most of a window's ranking kept, and so the deepest a filter can reach. Far more than the 200
		 * one answer may hold, so that a narrow genre still fills its chart, and about 300 KB per window.
		 */
		const val MAX_RANKED = 1000

		/** Works whose sources are looked up at a time while filling a ranking. */
		private const val SOURCE_BATCH = 100

		/**
		 * Walks the ranking until it is full, because a work nobody could open is worth nothing to list
		 * and cutting the ranking first would hand back a short chart.
		 */
		private fun buildWorks(works: WorkRepository, window: ChartWindow): List<SimilarWorkDto> {
			val listed = ArrayList<Pair<SimilarWork, List<WorkSource>>>()
			for (batch in works.chartCandidates(window.hours).chunked(SOURCE_BATCH)) {
				val sources = works.sourcesForAll(batch.map { it.workId }, limit = SimilarWorks.MAX_SOURCES_PER_SIMILAR)
				batch.forEach { candidate ->
					sources[candidate.workId]?.let { listed += candidate to it }
				}
				if (listed.size >= MAX_RANKED) break
			}
			val ranking = listed.take(MAX_RANKED)
			val genres = works.genresOfAll(ranking.map { (candidate, _) -> candidate.workId })
			return ranking.map { (candidate, sources) ->
				candidate.toDto(genres[candidate.workId].orEmpty(), sources)
			}
		}
	}
}
