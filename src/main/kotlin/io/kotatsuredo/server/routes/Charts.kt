package io.kotatsuredo.server.routes

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
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
import java.util.concurrent.TimeUnit
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
 * [schedule] keeps the rankings fresh from the background, and it is the only thing that rebuilds one:
 * a request is always answered with the ranking there is, however old, and `generated_at` says how old.
 * The scheduled build runs under the background limits of [backgroundBuild], because it is the one that
 * must be allowed to take long; a request never has a reason to wait for the aggregate.
 *
 * Each refresh also writes its ranking to [store], and a new instance loads what is stored, so a server
 * that has just started answers at once instead of building under the load of a start. A stored ranking
 * is served whatever its age, because an old chart beats an error and the first refresh replaces it. Only
 * a window with no ranking at all, such as in a database that never had one, is built by a request: one
 * request builds it, and the others wait a moment for that and are then told to come back, rather than
 * fill the request slots while a slow first build runs. If [store] cannot be read or written that is
 * logged and charts work as they would without one.
 */
class Charts(
	private val build: (ChartWindow) -> List<SimilarWorkDto>,
	private val clock: Clock = Clock.systemUTC(),
	private val ttl: Duration = DEFAULT_TTL,
	private val store: ChartStore? = null,
	private val backgroundBuild: (ChartWindow) -> List<SimilarWorkDto> = build,
	private val waitForFirstBuild: Duration = WAIT_FOR_FIRST_BUILD,
) {

	constructor(works: WorkRepository) : this(
		build = { window -> buildWorks(works, window, background = false) },
		store = DatabaseChartStore(works.dataSource),
		backgroundBuild = { window -> buildWorks(works, window, background = true) },
	)

	private class Ranking(val generatedAt: Long, val works: List<SimilarWorkDto>)

	private class Slot {
		val lock = ReentrantLock()

		@Volatile
		var ranking: Ranking? = null
	}

	private val slots = ChartWindow.entries.associateWith { Slot() }

	init {
		try {
			store?.load()?.forEach { (window, stored) ->
				slots.getValue(window).ranking = Ranking(stored.generatedAt, stored.works)
			}
		} catch (e: Exception) {
			logger.warn("Could not load the stored charts, they will be built instead", e)
		}
	}

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

	/**
	 * Rebuilds a window now, under the background limits, and stores the result. A failure leaves what is
	 * there, and a rebuild already running is enough.
	 */
	fun refresh(window: ChartWindow) {
		val slot = slots.getValue(window)
		if (slot.ranking == null) {
			// Nothing is being served, and whoever holds the lock is a request trying to build under the
			// short limits of a request. This is the build that is allowed to take long and whose result is
			// kept, so it waits its turn rather than leaving the window empty until the next round.
			slot.lock.lock()
		} else if (!slot.lock.tryLock()) {
			return
		}
		try {
			val ranking = Ranking(clock.millis(), backgroundBuild(window))
			slot.ranking = ranking
			save(window, ranking)
		} catch (e: Exception) {
			logger.warn("Could not refresh the {} chart", window.wire, e)
		} finally {
			slot.lock.unlock()
		}
	}

	/** For a genre no chart can carry: nothing to ask or keep. */
	fun emptyFor(window: ChartWindow) = ChartsResponse(window.wire, clock.millis(), emptyList())

	/** Costs nothing else when it fails: the ranking is already being served from memory. */
	private fun save(window: ChartWindow, ranking: Ranking) {
		try {
			store?.save(window, StoredRanking(ranking.generatedAt, ranking.works))
		} catch (e: Exception) {
			logger.warn("Could not store the {} chart", window.wire, e)
		}
	}

	private fun rankingFor(window: ChartWindow): Ranking {
		val slot = slots.getValue(window)
		// However old: rebuilding is the background refresh's job, and an old chart beats a slow request.
		slot.ranking?.let { return it }
		// Nothing to serve. One request builds it, and the others wait for that rather than each running it.
		// Not for long, though: the lock may be held by the background refresh, which is allowed minutes, and
		// a request waiting that long would sit on one of the few request slots, which the health check needs
		// too. Past the wait the caller is told to come back, as it is when the server is short of room.
		if (!slot.lock.tryLock(waitForFirstBuild.toMillis(), TimeUnit.MILLISECONDS)) {
			slot.ranking?.let { return it }
			throw ApiException(ApiError.Overloaded(RETRY_FIRST_BUILD_AFTER_S, "charts"))
		}
		try {
			slot.ranking?.let { return it }
			return Ranking(clock.millis(), build(window)).also { slot.ranking = it }
		} finally {
			slot.lock.unlock()
		}
	}

	companion object {

		private val logger = LoggerFactory.getLogger(Charts::class.java)

		val DEFAULT_TTL: Duration = Duration.ofMinutes(15)

		/**
		 * How long a request waits for the first ranking of a window while somebody else is building it:
		 * enough to pick up a build that is about to finish, and short enough that a row of such requests
		 * cannot fill the request slots for the length of a slow build.
		 */
		private val WAIT_FOR_FIRST_BUILD: Duration = Duration.ofSeconds(3)

		/** What a caller turned away for lack of a first ranking is told to wait: a build and a little more. */
		private const val RETRY_FIRST_BUILD_AFTER_S = 30L

		/** Long enough for a starting server to finish what is more urgent. */
		private val WARM_UP_DELAY: Duration = Duration.ofSeconds(30)

		/** A refresh starts this long before the ranking is [ttl] old, so the next one is never late. */
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
		private fun buildWorks(works: WorkRepository, window: ChartWindow, background: Boolean): List<SimilarWorkDto> {
			val listed = ArrayList<Pair<SimilarWork, List<WorkSource>>>()
			for (batch in works.chartCandidates(window.hours, background).chunked(SOURCE_BATCH)) {
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
