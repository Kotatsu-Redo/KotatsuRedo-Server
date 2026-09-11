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
		repository.set(workId, userId, value)
		val aggregate = recompute(workId)
		detectBrigade(workId)
		return aggregate
	}

	fun clear(workId: Long, userId: String): RatingAggregate {
		repository.delete(workId, userId)
		return recompute(workId)
	}

	/** The works this user has rated, so their aggregates can be rebuilt after an account erasure. */
	fun workIdsRatedBy(userId: String): List<Long> = repository.workIdsRatedBy(userId)

	fun allRatingsBy(userId: String): List<ExportedRating> = repository.allBy(userId)

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
	 */
	fun detectBrigade(workId: Long): Boolean {
		val recent = repository.recentRatings(workId, WINDOW_HOURS)
		if (recent.size < MIN_RATINGS_TO_FLAG) return false

		val older = repository.ratingsBefore(workId, WINDOW_HOURS)
		// A work with no history cannot be spiking. Without this guard the baseline is zero, every
		// comparison against it succeeds, and a brand-new work collecting its first ratings looks
		// exactly like an attack - which on a young instance is every work.
		if (older < MIN_HISTORY_TO_COMPARE) return false

		val baseline = older.toDouble() / BASELINE_DAYS
		if (recent.size < baseline * SPIKE_MULTIPLE) return false

		val extremeShare = recent.count { (value, _) -> value <= EXTREME_LOW || value >= EXTREME_HIGH }
			.toDouble() / recent.size
		if (extremeShare < EXTREME_SHARE_THRESHOLD) return false

		val newUserShare = recent.count { (_, tier) -> tier == 0 }.toDouble() / recent.size
		if (newUserShare < NEW_USER_SHARE_THRESHOLD) return false

		repository.flagBrigade(workId, recent.size, baseline, extremeShare, newUserShare)
		log.info(
			"Rating brigade flagged on work {}: {} recent vs baseline {}, {}% extreme, {}% new",
			workId, recent.size, "%.2f".format(baseline),
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
		const val EXTREME_SHARE_THRESHOLD = 0.80
		const val NEW_USER_SHARE_THRESHOLD = 0.60
	}
}
