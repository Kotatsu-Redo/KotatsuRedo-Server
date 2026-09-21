package io.kotatsuredo.server.ratings

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("RatingService")

class RatingService(private val repository: RatingRepository) {

	/**
	 * Records a rating and refreshes the work's aggregate.
	 *
	 * Recomputed on write rather than on a schedule: ratings per work are few, the read path is the
	 * details screen and must be one indexed row, and a stale average is the kind of thing people
	 * notice and report as a bug.
	 */
	fun rate(workId: Long, userId: String, value: Int): RatingAggregate {
		require(RatingScale.isValid(value)) { "rating out of range: $value" }
		val aggregate = repository.setAndAggregate(workId, userId, value)
		if (aggregate.count >= MIN_RATINGS_TO_FLAG) detectBrigade(workId)
		return aggregate
	}

	fun clear(workId: Long, userId: String): RatingAggregate =
		repository.deleteAndAggregate(workId, userId).second

	/** The works this user has rated, so their aggregates can be rebuilt after an account erasure. */
	fun workIdsRatedBy(userId: String): List<Long> = repository.workIdsRatedBy(userId)

	fun allRatingsBy(userId: String): List<ExportedRating> = repository.allBy(userId)

	fun allRatingsByPage(userId: String, afterWorkId: Long, limit: Int): List<ExportedRating> =
		repository.allByPage(userId, afterWorkId, limit)

	fun myRating(workId: Long, userId: String): Int? = repository.find(workId, userId)

	fun aggregate(workId: Long): RatingAggregate =
		repository.aggregate(workId) ?: RatingAggregate(workId, 0, 0.0, 0.0, List(RatingScale.BUCKETS) { 0 })

	fun recompute(workId: Long): RatingAggregate {
		val (count, mean, histogram) = repository.rawStats(workId)
		val aggregate = RatingAggregate(
			workId = workId,
			count = count,
			mean = mean,
			bayesian = bayesianAverage(count, mean, repository.globalMean()),
			histogram = histogram,
		)
		repository.storeAggregate(aggregate)
		return aggregate
	}

	/**
	 * Detects a coordinated review-bomb.
	 *
	 * All three conditions must hold at once, because each alone is ordinary:
	 *
	 *  - a **spike** relative to the work's own history is a manga getting popular;
	 *  - **extreme** ratings are what people give things they love or hate;
	 *  - a share of **new accounts** is what a growing userbase looks like.
	 *
	 * Together they are coordination. Nothing is reverted - a flag goes to the panel and a human
	 * decides, because being wrong here means deleting real people's opinions.
	 *
	 * Only extremes *against* the work's existing lean count. Fans of a well-liked series giving it
	 * five stars are extreme, new and bursty all at once, and are the opposite of an attack; this
	 * was the first false positive in production (a popular manhwa on a week-old instance).
	 */
	fun detectBrigade(workId: Long): Boolean {
		val stats = repository.brigadeStats(workId, WINDOW_HOURS, BASELINE_DAYS.toInt(), EXTREME_LOW, EXTREME_HIGH)
		if (stats.recentCount < MIN_RATINGS_TO_FLAG) return false

		// A work with no history cannot be spiking. Without this guard the baseline is zero, every
		// comparison against it succeeds, and a brand-new work collecting its first ratings looks
		// exactly like an attack - which on a young instance is every work.
		if (stats.olderCount < MIN_HISTORY_TO_COMPARE) return false

		// Spread the baseline over the time it actually covers. Thirty ratings from yesterday are
		// thirty a day, not one; dividing by a fixed thirty days turned a work's second day into a
		// thirtyfold spike. Floored at a day so a burst just before the window is not a huge rate.
		val baselineDays = stats.baselineSpanDays.coerceIn(1.0, BASELINE_DAYS)
		val baseline = stats.baselineCount / baselineDays
		if (stats.recentCount < baseline * SPIKE_MULTIPLE) return false

		val extremeShare = if (stats.olderMean >= RATING_MIDPOINT) stats.lowShare else stats.highShare
		if (extremeShare < EXTREME_SHARE_THRESHOLD) return false

		val newUserShare = stats.newUserShare
		if (newUserShare < NEW_USER_SHARE_THRESHOLD) return false

		repository.flagBrigade(workId, stats.recentCount, baseline, extremeShare, newUserShare)
		log.info(
			"Rating brigade flagged on work {}: {} recent vs baseline {}, {}% extreme, {}% new",
			workId, stats.recentCount, "%.2f".format(baseline),
			(extremeShare * 100).toInt(), (newUserShare * 100).toInt(),
		)
		return true
	}

	companion object {

		/**
		 * Pulls a work with few ratings toward the global mean, so a single five-star cannot outrank a
		 * five-hundred-vote 4.4. `C` is the number of "average" votes every work is treated as already
		 * having: high enough to matter, low enough that a genuinely well-rated work escapes it.
		 */
		const val PRIOR_WEIGHT = 20.0

		fun bayesianAverage(count: Int, mean: Double, globalMean: Double): Double {
			if (count <= 0) return 0.0
			return (PRIOR_WEIGHT * globalMean + count * mean) / (PRIOR_WEIGHT + count)
		}

		const val WINDOW_HOURS = 24
		const val BASELINE_DAYS = 30.0
		const val MIN_RATINGS_TO_FLAG = 10

		/**
		 * Ratings a work needs before a "spike" means anything. Also the reason a young instance does
		 * not flag everything: on day one every user is tier 0 and every work is new, so the other two
		 * conditions are trivially satisfied.
		 */
		const val MIN_HISTORY_TO_COMPARE = 30
		const val SPIKE_MULTIPLE = 5.0
		const val EXTREME_LOW = 2
		const val EXTREME_HIGH = 9

		/** Middle of the 1..10 scale: a work whose history averages at or above it leans positive. */
		const val RATING_MIDPOINT = (RatingScale.MIN + RatingScale.MAX) / 2.0
		const val EXTREME_SHARE_THRESHOLD = 0.80
		const val NEW_USER_SHARE_THRESHOLD = 0.60
	}
}
