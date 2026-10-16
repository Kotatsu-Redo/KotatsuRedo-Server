package io.kotatsuredo.server.scoring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val log = LoggerFactory.getLogger("ScoringService")

class ScoringService(
	private val repository: ScoringRepository,
	private val clock: Clock = Clock.systemUTC(),
) {
	private val snapshotCache = ConcurrentHashMap<String, ScoreSnapshot>()

	/**
	 * Recomputes every source's score from the decayed probe window.
	 *
	 * Popularity is relative to the busiest source *in the same region*, not globally: a region with
	 * a tenth of the traffic would otherwise have every source scored near zero, and the fire icon in
	 * the app would light up nothing at all for those users.
	 */
	fun recompute(): Int {
		val aggregates = repository.aggregate()
		val now = OffsetDateTime.now(clock)
		if (aggregates.isEmpty()) {
			val pruned = repository.pruneStale(now)
			if (pruned > 0) snapshotCache.clear()
			log.debug("No probe data to score")
			return 0
		}

		val maxReporterWeightByRegion = aggregates
			.groupBy { it.region }
			.mapValues { (_, rows) -> rows.maxOf { it.reporterWeight } }

		val scores = aggregates.map { aggregate ->
			val stability = Scoring.stability(
				okWeighted = aggregate.okWeighted,
				failWeighted = aggregate.failWeighted,
				emptyWeighted = aggregate.emptyWeighted,
				cfBlockedWeighted = aggregate.cfBlockedWeighted,
				latencyP50Ms = aggregate.latencyP50Ms,
			)
			val popularity = Scoring.popularity(
				reporterWeight = aggregate.reporterWeight,
				maxReporterWeight = maxReporterWeightByRegion.getValue(aggregate.region),
			)
			SourceScore(
				source = aggregate.source,
				region = aggregate.region,
				stability = stability,
				popularity = popularity,
				composite = Scoring.composite(stability, popularity),
				sampleSize = aggregate.sampleSize,
			)
		}.groupBy { it.region }.values.flatMap { regionScores ->
			// The public blob is deliberately bounded. Prefer well-corroborated sources, then the best
			// score, so one-reporter names cannot crowd established sources out of the response.
			regionScores.sortedWith(
				compareByDescending<SourceScore> { it.sampleSize }
					.thenByDescending { it.composite }
					.thenBy { it.source },
			).take(MAX_SCORES_PER_REGION)
		}

		val written = repository.replace(scores, now)
		val opsWritten = repository.replaceOpScores(opScores(scores), now)
		snapshotCache.clear()
		log.info(
			"Recomputed {} source score(s) and {} per-operation score(s) across {} region(s)",
			written, opsWritten, maxReporterWeightByRegion.size,
		)
		return written
	}

	/**
	 * Stability per operation, for the sources that made the region's budget.
	 *
	 * Scoped to those sources so the two tables cannot disagree about who exists, and because a
	 * per-operation figure for a source the client is never told about is not worth storing.
	 */
	private fun opScores(kept: List<SourceScore>): List<SourceOpScore> {
		val keptKeys = kept.mapTo(HashSet()) { it.source to it.region }
		return repository.aggregateByOp()
			.filter { (it.source to it.region) in keptKeys }
			.mapNotNull { aggregate ->
				val op = aggregate.op ?: return@mapNotNull null
				SourceOpScore(
					source = aggregate.source,
					region = aggregate.region,
					op = op,
					stability = Scoring.stability(
						okWeighted = aggregate.okWeighted,
						failWeighted = aggregate.failWeighted,
						emptyWeighted = aggregate.emptyWeighted,
						cfBlockedWeighted = aggregate.cfBlockedWeighted,
						latencyP50Ms = aggregate.latencyP50Ms,
					),
					sampleSize = aggregate.sampleSize,
				)
			}
	}

	fun scores(region: String): ScoreSnapshot = snapshotCache.computeIfAbsent(region, ::loadScores)

	private fun loadScores(region: String): ScoreSnapshot {
		val scores = repository.scoresFor(region)
		val ops = repository.opScoresFor(region)
		return ScoreSnapshot(
			region = region,
			generatedAt = repository.lastUpdated(region),
			scores = scores,
			medianComposite = scores.map { it.composite }.median(),
			medianStability = scores.map { it.stability }.median(),
			ops = ops,
			// A per-operation sample is a slice of the blended one, so thin figures are commoner
			// here and the prior matters more: an unproven operation sits at its own median rather
			// than at the blended one, which would flatter a source broken at exactly that job.
			medianStabilityByOp = ops.values.flatten().groupBy { it.op }
				.mapValues { (_, rows) -> rows.map { it.stability }.median() },
		)
	}

	fun schedule(scope: CoroutineScope, interval: Duration = 6.hours) = scope.launch(Dispatchers.IO) {
		while (isActive) {
			runCatching { recompute() }.onFailure { log.warn("Score recomputation failed", it) }
			delay(interval)
		}
	}

	private fun List<Double>.median(): Double {
		if (isEmpty()) return 0.0
		val sorted = sorted()
		val middle = sorted.size / 2
		return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
	}

	private companion object {
		const val MAX_SCORES_PER_REGION = 1_200
	}
}

/**
 * [medianComposite] is what makes the exploration prior work.
 *
 * A source with too little data must not score near zero, because then it is never queried, so it
 * never earns data, so it is never queried - the starvation loop in PLAN.md §4. The client scores
 * anything below its sample threshold at this median instead: unknown means unproven, not bad.
 */
data class ScoreSnapshot(
	val region: String,
	val generatedAt: OffsetDateTime?,
	val scores: List<SourceScore>,
	val medianComposite: Double,
	val medianStability: Double,
	/** Per-operation stability, keyed by source. Empty for a source with no operation data yet. */
	val ops: Map<String, List<SourceOpScore>> = emptyMap(),
	/** The optimistic prior, per operation - see [loadScores]. */
	val medianStabilityByOp: Map<Short, Double> = emptyMap(),
)
