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
 * Deletes raw per-reporter probe rows once they are older than the retention window.
 *
 * This is the mechanism behind the sentence "raw probe rows are deleted within 7 days" in the privacy
 * notice, so it is a promise to users rather than a cleanup job. If it stops running, the claim stops
 * being true - hence the warning log on failure rather than a silent swallow.
 */
class TelemetryRetention(
	private val repository: TelemetryRepository,
	private val clock: Clock = Clock.systemUTC(),
	private val retentionDays: Long = RETENTION_DAYS,
) {

	fun purgeNow(): Int {
		val cutoff = LocalDate.now(clock).minusDays(retentionDays)
		val deleted = repository.purgeOlderThan(cutoff)
		if (deleted > 0) {
			log.info("Purged {} raw probe row(s) older than {}", deleted, cutoff)
		}
		return deleted
	}

	fun schedule(scope: CoroutineScope, interval: Duration = 6.hours) = scope.launch(Dispatchers.IO) {
		while (isActive) {
			runCatching { purgeNow() }
				.onFailure { log.warn("Telemetry purge failed; retention claim is at risk", it) }
			delay(interval)
		}
	}

	companion object {
		const val RETENTION_DAYS = 7L
	}
}
