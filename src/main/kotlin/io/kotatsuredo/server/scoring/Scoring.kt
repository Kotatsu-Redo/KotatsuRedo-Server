package io.kotatsuredo.server.scoring

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The scoring maths, kept pure so it can be tested without a database.
 *
 * Everything here is deliberately monotonic and bounded to 0..1: these numbers are both a sort key
 * and something the app shows as a reliability indicator, so a value that cannot be read as
 * "fraction of good" would be a trap for whoever renders it next.
 */
object Scoring {

	/** Latency at or below this is not penalised at all. */
	const val LATENCY_FLOOR_MS = 800.0

	/** Latency this much above the floor scores zero. */
	const val LATENCY_RANGE_MS = 5_000.0

	private const val SUCCESS_WEIGHT = 0.55
	private const val LATENCY_WEIGHT = 0.25
	private const val CF_PENALTY = 0.7
	private const val EMPTY_PENALTY = 0.4

	private const val STABILITY_GLOBAL_WEIGHT = 0.40
	private const val POPULARITY_GLOBAL_WEIGHT = 0.20

	/**
	 * Wilson score lower bound.
	 *
	 * The reason the plain ratio is not used: a source with one success and no failures has a ratio
	 * of 1.0 and would outrank a source with 4,000 successes and 12 failures. The lower bound asks
	 * "what is the worst this could plausibly be, given how little we have seen", which is exactly
	 * the question a ranking should be asking.
	 */
	fun wilsonLowerBound(successes: Double, total: Double, z: Double = 1.96): Double {
		if (total <= 0.0 || successes < 0.0) return 0.0
		val p = (successes / total).coerceIn(0.0, 1.0)
		val z2 = z * z
		val denominator = 1.0 + z2 / total
		val centre = p + z2 / (2.0 * total)
		val margin = z * sqrt((p * (1.0 - p) + z2 / (4.0 * total)) / total)
		return ((centre - margin) / denominator).coerceIn(0.0, 1.0)
	}

	fun latencyScore(p50Ms: Double): Double =
		(1.0 - (p50Ms - LATENCY_FLOOR_MS) / LATENCY_RANGE_MS).coerceIn(0.0, 1.0)

	/**
	 * Note the normalisation by the weight sum. The design document writes this as
	 * `0.55·success + 0.25·latency`, which tops out at 0.80 - fine as a sort key, misleading as a
	 * displayed "reliability" figure. Dividing by the weights preserves the ordering exactly and
	 * makes the number mean what a reader will assume it means.
	 */
	fun stability(
		okWeighted: Double,
		failWeighted: Double,
		emptyWeighted: Double,
		cfBlockedWeighted: Double,
		latencyP50Ms: Double,
	): Double {
		val total = okWeighted + failWeighted
		if (total <= 0.0) return 0.0

		val success = wilsonLowerBound(okWeighted, total)
		val latency = latencyScore(latencyP50Ms)
		val base = (SUCCESS_WEIGHT * success + LATENCY_WEIGHT * latency) / (SUCCESS_WEIGHT + LATENCY_WEIGHT)

		val cfPenalty = (cfBlockedWeighted / total).coerceIn(0.0, 1.0)
		// "200 OK with zero results" is a broken parser, not a failure - it never shows up in `fail`,
		// so without this term a comprehensively broken source looks perfectly healthy.
		val emptyPenalty = (emptyWeighted / maxOf(okWeighted, 1.0)).coerceIn(0.0, 1.0)

		return (base * (1.0 - CF_PENALTY * cfPenalty) * (1.0 - EMPTY_PENALTY * emptyPenalty))
			.coerceIn(0.0, 1.0)
	}

	/**
	 * Log-scaled so that the largest source does not flatten everything else to zero, and relative
	 * to the busiest source rather than to an absolute number that would drift as the userbase grows.
	 */
	fun popularity(reporterWeight: Double, maxReporterWeight: Double): Double {
		if (reporterWeight <= 0.0 || maxReporterWeight <= 0.0) return 0.0
		return (ln(1.0 + reporterWeight) / ln(1.0 + maxReporterWeight)).coerceIn(0.0, 1.0)
	}

	/**
	 * The *global* half of the blend only. The app adds its own local success history (weighted more
	 * heavily as it accumulates) and language affinity - see PLAN.md §4. Normalised by its weights
	 * for the same reason as [stability].
	 */
	fun composite(stability: Double, popularity: Double): Double =
		((STABILITY_GLOBAL_WEIGHT * stability + POPULARITY_GLOBAL_WEIGHT * popularity) /
			(STABILITY_GLOBAL_WEIGHT + POPULARITY_GLOBAL_WEIGHT)).coerceIn(0.0, 1.0)

	/**
	 * Exponential decay with a seven-day half-life: a source that broke yesterday falls fast, and one
	 * that recovered climbs back within a week.
	 */
	fun decayWeight(ageInDays: Double, halfLifeDays: Double = 7.0): Double =
		Math.pow(0.5, ageInDays / halfLifeDays)
}
