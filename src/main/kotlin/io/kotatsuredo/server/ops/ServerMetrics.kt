package io.kotatsuredo.server.ops

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What the server is doing right now, for the panel's health view.
 *
 * Everything here was previously answerable only by grepping container logs - which is how a day of
 * refused resolutions went unnoticed, and how the evidence then vanished with the next deploy. A
 * moderator should be able to see "one request in twelve is being turned away" without an ssh key.
 *
 * A ring of per-minute counters rather than a metrics library: this is a single instance with one
 * question to answer, the numbers cost nothing to keep, and there is no scrape endpoint to secure.
 * Counters live in memory and start again with the process, which is honest - the panel shows uptime
 * beside them so a small window is obviously a small window.
 */
object ServerMetrics {

	const val WINDOW_MINUTES = 60

	private val startedAtMillis = System.currentTimeMillis()
	private val minutes = Array(WINDOW_MINUTES) { Minute() }
	private val refusalsByBucket = ConcurrentHashMap<String, AtomicLong>()
	private val overloadsByResource = ConcurrentHashMap<String, AtomicLong>()

	val uptimeSeconds: Long get() = (System.currentTimeMillis() - startedAtMillis) / 1_000

	private class Minute {
		@Volatile
		var stamp: Long = -1
		val total = AtomicLong()
		val ok = AtomicLong()
		val clientError = AtomicLong()
		val rateLimited = AtomicLong()
		val overloaded = AtomicLong()
		val serverError = AtomicLong()

		/** Zeroes the slot the first time this minute lands on it, an hour after its last use. */
		fun rollTo(minute: Long) {
			if (stamp == minute) return
			synchronized(this) {
				if (stamp == minute) return
				stamp = minute
				total.set(0); ok.set(0); clientError.set(0)
				rateLimited.set(0); overloaded.set(0); serverError.set(0)
			}
		}
	}

	private fun currentMinute(): Long = System.currentTimeMillis() / 60_000

	private fun slot(minute: Long): Minute =
		minutes[(minute % WINDOW_MINUTES).toInt()].also { it.rollTo(minute) }

	/**
	 * Every answered request. A 429 only lands in [total] here: which kind of 429 it was is recorded
	 * where it is decided, because the status code alone cannot tell a quota from a busy pool.
	 */
	fun recordResponse(status: Int) {
		val slot = slot(currentMinute())
		slot.total.incrementAndGet()
		when {
			status in 200..399 -> slot.ok.incrementAndGet()
			status == 429 -> Unit
			status in 400..499 -> slot.clientError.incrementAndGet()
			status >= 500 -> slot.serverError.incrementAndGet()
		}
	}

	/** A quota refusal: this caller has had its share of [bucket] for now. */
	fun recordRateLimited(bucket: String) {
		slot(currentMinute()).rateLimited.incrementAndGet()
		refusalsByBucket.computeIfAbsent(bucket) { AtomicLong() }.incrementAndGet()
	}

	/** A capacity refusal: nothing the caller did, and the same request would work in a moment. */
	fun recordOverloaded(resource: String) {
		slot(currentMinute()).overloaded.incrementAndGet()
		overloadsByResource.computeIfAbsent(resource) { AtomicLong() }.incrementAndGet()
	}

	data class Window(
		val minutes: Int,
		val total: Long,
		val ok: Long,
		val clientError: Long,
		val rateLimited: Long,
		val overloaded: Long,
		val serverError: Long,
	) {
		/** Share of requests turned away for any reason, which is the number worth watching. */
		val refusedShare: Double
			get() = if (total == 0L) 0.0 else (rateLimited + overloaded + serverError).toDouble() / total
	}

	fun window(minutes: Int): Window {
		val now = currentMinute()
		var total = 0L; var ok = 0L; var client = 0L; var limited = 0L; var over = 0L; var server = 0L
		for (offset in 0 until minutes.coerceIn(1, WINDOW_MINUTES)) {
			val minute = now - offset
			val slot = this.minutes[(minute % WINDOW_MINUTES + WINDOW_MINUTES).toInt() % WINDOW_MINUTES]
			// A slot whose stamp has moved on belongs to another hour, so it says nothing about this one.
			if (slot.stamp != minute) continue
			total += slot.total.get(); ok += slot.ok.get(); client += slot.clientError.get()
			limited += slot.rateLimited.get(); over += slot.overloaded.get(); server += slot.serverError.get()
		}
		return Window(minutes, total, ok, client, limited, over, server)
	}

	fun refusalsByBucket(): Map<String, Long> = refusalsByBucket.mapValues { it.value.get() }

	fun overloadsByResource(): Map<String, Long> = overloadsByResource.mapValues { it.value.get() }

	/** Test seam: the ring is process-wide, so a test that counts has to start from a known state. */
	fun reset() {
		minutes.forEach { it.rollTo(-2) }
		refusalsByBucket.clear()
		overloadsByResource.clear()
	}
}
