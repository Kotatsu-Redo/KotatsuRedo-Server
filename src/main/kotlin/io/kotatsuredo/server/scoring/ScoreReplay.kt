package io.kotatsuredo.server.scoring

import java.time.LocalDate
import javax.sql.DataSource
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Does the ranking predict what happens next?
 *
 * §4 rules out an analytics SDK, and the weights in it are openly guesses. This is the instrument
 * that replaces the A/B test the plan declines to run: score every source from the days up to `d`,
 * then check the ranking against what those sources actually did on `d + 1`. A weight change that
 * helps shows up as a better number here, across many days, or it does not.
 *
 * It reads the daily rollup, never the raw per-reporter rows, so it works over months of history and
 * touches nothing that identifies anyone.
 */
class ScoreReplay(private val dataSource: DataSource) {

	/** One source's behaviour on one day, as the rollup recorded it. */
	data class DayRow(
		val source: String,
		val region: String,
		val day: LocalDate,
		val reporters: Int,
		val ok: Long,
		val fail: Long,
		val empty: Long,
		val cfBlocked: Long,
		val latencyMeanMs: Int,
	) {
		val attempts: Long get() = ok + fail

		/** What the ranking is trying to predict: the share of attempts that worked. */
		val okRate: Double get() = if (attempts <= 0) 0.0 else ok.toDouble() / attempts
	}

	data class Result(
		val days: Int,
		val pairs: Int,
		/** Ranking quality: 1.0 means the order matched tomorrow's success exactly. */
		val ndcg: Double,
		/** Calibration: mean squared error of stability against tomorrow's ok-rate. Lower is better. */
		val brier: Double,
		/** The naive alternative - rank by yesterday's raw ok-rate - for comparison. */
		val ndcgNaive: Double,
	)

	/**
	 * @param weights the scoring parameters to test, so a candidate can be replayed without being
	 *  deployed first.
	 */
	fun run(
		from: LocalDate,
		to: LocalDate,
		region: String,
		weights: Weights = Weights(),
		topK: Int = 10,
	): Result {
		val rows = load(from.minusDays(weights.windowDays.toLong()), to, region)
		if (rows.isEmpty()) return Result(0, 0, 0.0, 0.0, 0.0)

		val byDay = rows.groupBy { it.day }
		var ndcgSum = 0.0
		var naiveSum = 0.0
		var brierSum = 0.0
		var pairs = 0
		var days = 0

		var day = from
		while (!day.isAfter(to)) {
			val tomorrow = byDay[day.plusDays(1)].orEmpty().filter { it.attempts > 0 }
			if (tomorrow.isEmpty()) {
				day = day.plusDays(1)
				continue
			}
			val history = rows.filter { !it.day.isAfter(day) && it.day.isAfter(day.minusDays(weights.windowDays.toLong())) }
			if (history.isEmpty()) {
				day = day.plusDays(1)
				continue
			}

			val predicted = stabilityAsOf(history, day, weights)
			val naive = history.groupBy { it.source }
				.mapValues { (_, source) -> source.maxByOrNull { it.day }?.okRate ?: 0.0 }
			val truth = tomorrow.associate { it.source to it.okRate }

			val ranked = truth.keys.filter { it in predicted }
			if (ranked.isEmpty()) {
				day = day.plusDays(1)
				continue
			}

			ndcgSum += ndcg(ranked.sortedByDescending { predicted.getValue(it) }, truth, topK)
			naiveSum += ndcg(ranked.sortedByDescending { naive[it] ?: 0.0 }, truth, topK)
			ranked.forEach { source ->
				val error = predicted.getValue(source) - truth.getValue(source)
				brierSum += error * error
				pairs++
			}
			days++
			day = day.plusDays(1)
		}

		return Result(
			days = days,
			pairs = pairs,
			ndcg = if (days == 0) 0.0 else ndcgSum / days,
			brier = if (pairs == 0) 0.0 else brierSum / pairs,
			ndcgNaive = if (days == 0) 0.0 else naiveSum / days,
		)
	}

	/**
	 * The live stability formula, fed from the rollup rather than from per-reporter rows.
	 *
	 * Device health cannot be reconstructed without reporters, so this is the scoring maths minus
	 * that one filter. It is the same for every candidate weighting being compared, so it does not
	 * bias the comparison - but it does mean a number here is not the number the server would print.
	 */
	private fun stabilityAsOf(history: List<DayRow>, asOf: LocalDate, weights: Weights): Map<String, Double> =
		history.groupBy { it.source }.mapValues { (_, days) ->
			var ok = 0.0
			var fail = 0.0
			var empty = 0.0
			var cf = 0.0
			var latencyNumerator = 0.0
			var latencyDenominator = 0.0
			days.forEach { row ->
				val age = (asOf.toEpochDay() - row.day.toEpochDay()).toDouble()
				val decay = 0.5.pow(age / weights.halfLifeDays)
				val evidence = decay * row.reporters
				val attempts = row.attempts.toDouble().coerceAtLeast(1.0)
				ok += row.ok / attempts * evidence
				fail += row.fail / attempts * evidence
				empty += row.empty / attempts * evidence
				cf += row.cfBlocked / attempts * evidence
				latencyNumerator += row.latencyMeanMs * evidence
				latencyDenominator += evidence
			}
			Scoring.stability(
				okWeighted = ok,
				failWeighted = fail,
				emptyWeighted = empty,
				cfBlockedWeighted = cf,
				latencyP50Ms = if (latencyDenominator <= 0.0) 0.0 else latencyNumerator / latencyDenominator,
			)
		}

	/** Normalised discounted cumulative gain: how close the order is to the best possible order. */
	private fun ndcg(ranked: List<String>, truth: Map<String, Double>, topK: Int): Double {
		fun gain(order: List<String>) = order.take(topK)
			.mapIndexed { index, source -> truth.getValue(source) / ln((index + 2).toDouble()) }
			.sum()

		val ideal = gain(truth.keys.sortedByDescending { truth.getValue(it) })
		return if (abs(ideal) < 1e-12) 0.0 else (gain(ranked) / ideal).coerceIn(0.0, 1.0)
	}

	private fun load(from: LocalDate, to: LocalDate, region: String): List<DayRow> =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT source, region, day, reporters, ok, fail, empty, cf_blocked, latency_mean_ms
				FROM source_probe_daily
				WHERE region = ? AND day BETWEEN ? AND ?
				ORDER BY day, source
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, region)
				statement.setObject(2, from)
				statement.setObject(3, to.plusDays(1))
				statement.executeQuery().use { rows ->
					buildList {
						while (rows.next()) {
							add(
								DayRow(
									source = rows.getString(1),
									region = rows.getString(2),
									day = rows.getObject(3, LocalDate::class.java),
									reporters = rows.getInt(4),
									ok = rows.getLong(5),
									fail = rows.getLong(6),
									empty = rows.getLong(7),
									cfBlocked = rows.getLong(8),
									latencyMeanMs = rows.getInt(9),
								),
							)
						}
					}
				}
			}
		}

	/** The knobs worth replaying. Defaults are what the server runs today. */
	data class Weights(
		val halfLifeDays: Double = 7.0,
		val windowDays: Int = DEFAULT_WINDOW_DAYS,
	)

	private companion object {
		/** Mirrors ScoringRepository.WINDOW_DAYS, which is private to the scoring pipeline. */
		const val DEFAULT_WINDOW_DAYS = 30
	}
}
