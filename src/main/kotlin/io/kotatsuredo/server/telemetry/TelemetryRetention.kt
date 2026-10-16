package io.kotatsuredo.server.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val log = LoggerFactory.getLogger("TelemetryRetention")

/**
 * Rolls each day up, then deletes raw per-reporter rows older than the retention window.
 *
 * The deletion is the mechanism behind the sentence "raw probe rows are deleted within 7 days" in the
 * privacy notice, so it is a promise to users rather than a cleanup job. If it stops running, the
 * claim stops being true - hence the warning log on failure rather than a silent swallow.
 *
 * The rollup runs first and in the same job on purpose. It keeps the per-day totals that scoring and
 * the replay harness need, without a reporter column, and a day is rolled up on every pass from the
 * day after it ends - six days before its raw rows are due. The purge never waits for it: a day the
 * rollup somehow missed is deleted on time anyway and logged as an error, because the deletion is
 * the promise and the history is not.
 */
class TelemetryRetention(
	private val repository: TelemetryRepository,
	private val clock: Clock = Clock.systemUTC(),
	private val retentionDays: Long = RETENTION_DAYS,
) {

	/** Folds every complete day into the rollup. Today is left alone: its rows are still arriving. */
	fun rollUpNow(): Int {
		val through = LocalDate.now(clock).minusDays(1)
		val rows = repository.rollUpThrough(through)
		if (rows > 0) log.info("Rolled up {} source-day row(s) through {}", rows, through)
		return rows
	}

	/**
	 * Deletes raw rows past the retention window, whether or not the rollup has read them.
	 *
	 * The deletion is a promise in the privacy notice; history for tuning a ranking is not a reason to
	 * break it, even by a day. A day lost this way is logged as an error, because by then the rollup
	 * has been failing for most of a week.
	 */
	fun purgeNow(): Int {
		val cutoff = LocalDate.now(clock).minusDays(retentionDays)
		val unrolled = repository.oldestUnrolledDay()
		if (unrolled != null && unrolled.isBefore(cutoff)) {
			log.error(
				"Deleting raw probe rows from {} that never reached the daily rollup: retention comes " +
					"first, so this history is lost. The rollup has been failing for days.",
				unrolled,
			)
		}
		val deleted = repository.purgeOlderThan(cutoff)
		if (deleted > 0) {
			log.info("Purged {} raw probe row(s) older than {}", deleted, cutoff)
		}
		return deleted
	}

	fun schedule(scope: CoroutineScope, interval: Duration = 6.hours) = scope.launch(Dispatchers.IO) {
		while (isActive) {
			runCatching { rollUpNow() }
				.onFailure { log.warn("Telemetry rollup failed; scoring history will have a gap", it) }
			runCatching { purgeNow() }
				.onFailure { log.warn("Telemetry purge failed; retention claim is at risk", it) }
			delay(interval)
		}
	}

	companion object {
		const val RETENTION_DAYS = 7L
	}
}
