package io.kotatsuredo.server.works

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("CoReading")

/**
 * Keeps "readers who opened this also opened that" current: rebuilds the pair counts once a day.
 *
 * Daily because the signal moves slowly - a pair needs ten accounts before it exists at all - and
 * because a full rebuild is the simple way to keep a promise: an account deleted or sanctioned today
 * stops counting tomorrow, with no bookkeeping about what it once contributed.
 */
class CoReading(private val repository: WorkRepository) {

	fun refreshNow(): CoReadRefresh {
		val started = System.nanoTime()
		val result = repository.refreshCoReading()
		log.info(
			"Co-reading rebuilt in {} ms: {} pair(s) from {} account-work row(s), {} bucket(s), {} batch(es)",
			(System.nanoTime() - started) / 1_000_000,
			result.pairs,
			result.accountWorks,
			result.accountBuckets,
			result.pairBatches,
		)
		return result
	}

	/** Waits out startup first: a deploy should not open with the heaviest read the server makes. */
	fun schedule(
		scope: CoroutineScope,
		initialDelay: Duration = 20.minutes,
		interval: Duration = 24.hours,
	) = scope.launch(Dispatchers.IO) {
		delay(initialDelay)
		while (isActive) {
			runCatching { refreshNow() }
				.onFailure { log.warn("Co-reading rebuild failed; yesterday's pairs stay in use", it) }
			delay(interval)
		}
	}
}
