package io.kotatsuredo.server.auth

import io.kotatsuredo.server.identity.TrustTier
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Rate limits are the only volume control in the system.
 *
 * Nothing is held for review and there is no report button, so these numbers are what stop a script
 * posting ten thousand filter-clean comments overnight (PLAN.md §3, §6).
 *
 * Deliberately in-memory: a single instance needs no shared store, and the IP buckets must never be
 * persisted or logged for the privacy claim in §6 to stay literally true.
 */
/**
 * An operator-set limit, or the built-in default.
 *
 * Top level rather than a member, because enum entries are constructed before the class body exists.
 */
private fun envLimit(name: String, fallback: Int): Int =
	System.getenv(name)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

class RateLimiter(
	private val clock: Clock = Clock.systemUTC(),
	private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

	enum class Bucket(val limit: Map<TrustTier, Int>, val windowSeconds: Long) {
		COMMENTS(
			mapOf(
				// Tunable, because the right number depends on how big the community is and only the
				// operator knows that. Five an hour is careful for a public instance and miserly for a
				// small one - and for anybody testing their own server.
				TrustTier.NEW to envLimit("RATE_COMMENTS_NEW", 5),
				TrustTier.NORMAL to envLimit("RATE_COMMENTS_NORMAL", 20),
				TrustTier.ESTABLISHED to envLimit("RATE_COMMENTS_ESTABLISHED", 40),
			),
			3600,
		),
		COMMENTS_DAILY(mapOf(TrustTier.NEW to 15, TrustTier.NORMAL to 60, TrustTier.ESTABLISHED to 150), 86_400),
		THREAD_REPLIES(mapOf(TrustTier.NEW to 5, TrustTier.NORMAL to 10, TrustTier.ESTABLISHED to 15), 3600),
		VOTES(mapOf(TrustTier.NEW to 60, TrustTier.NORMAL to 200, TrustTier.ESTABLISHED to 400), 3600),
		RATINGS(mapOf(TrustTier.NEW to 30, TrustTier.NORMAL to 100, TrustTier.ESTABLISHED to 200), 3600),
		HELLO(mapOf(TrustTier.NEW to 5, TrustTier.NORMAL to 5, TrustTier.ESTABLISHED to 5), 3600),
		// Applied before bearer-token lookup, so random credentials cannot exhaust the DB pool.
		PRE_AUTH(mapOf(TrustTier.NEW to 60, TrustTier.NORMAL to 60, TrustTier.ESTABLISHED to 60), 60),
		ADMIN_LOGIN(mapOf(TrustTier.NEW to 5, TrustTier.NORMAL to 5, TrustTier.ESTABLISHED to 5), 900),
		// Probe rows per day. One normal batch fits for a new account; settled accounts may retry or
		// report a second device without turning batching into a cardinality multiplier.
		TELEMETRY(mapOf(TrustTier.NEW to 512, TrustTier.NORMAL to 1024, TrustTier.ESTABLISHED to 2048), 86_400),
		EXPORT(mapOf(TrustTier.NEW to 1, TrustTier.NORMAL to 1, TrustTier.ESTABLISHED to 1), 3600),
		// Charged per work in a resolve batch (links cost one). This keeps batching efficient without
		// making a 25-work request cost the same as a one-work request.
		WORK_MUTATION(mapOf(TrustTier.NEW to 100, TrustTier.NORMAL to 300, TrustTier.ESTABLISHED to 600), 3600),
		GENERAL(mapOf(TrustTier.NEW to 300, TrustTier.NORMAL to 300, TrustTier.ESTABLISHED to 300), 60),
		;

		fun limitFor(tier: TrustTier): Int = limit.getValue(tier)
	}

	/**
	 * Warns when a tier has been given less rope than a less-trusted one.
	 *
	 * The limits are operator-set now, and an inverted ladder is silent: a new account simply gets
	 * more than a settled one and nothing anywhere says so. Noisy rather than fatal, because a server
	 * refusing to start over a rate limit is a worse outcome than one running an odd one.
	 */
	fun validate(log: org.slf4j.Logger) {
		for (bucket in Bucket.entries) {
			val ladder = listOf(TrustTier.NEW, TrustTier.NORMAL, TrustTier.ESTABLISHED)
			ladder.zipWithNext { lower, higher ->
				val below = bucket.limitFor(lower)
				val above = bucket.limitFor(higher)
				if (below > above) {
					log.warn(
						"Rate limit for {} gives {} ({}) more than {} ({}). Trust is supposed to earn " +
							"more rope, not less.",
						bucket.name, lower, below, higher, above,
					)
				}
			}
		}
	}

	private data class Window(val startEpochSecond: Long, val count: Int)
	private data class RequestedWindow(val composite: String, val bucket: Bucket, val limit: Int, val cost: Int)
	internal data class ConsumedWindow(val startEpochSecond: Long, val cost: Int)

	private val windows = ConcurrentHashMap<String, Window>()
	private val locks = Array(LOCK_STRIPES) { Any() }
	private val capacityLock = Any()
	private val operations = AtomicLong()
	private val nextCapacityEvictionSecond = AtomicLong(Long.MIN_VALUE)

	/**
	 * @param commit false to ask whether there is room without taking any.
	 *
	 * Posting a comment asks first and takes only once the comment is actually stored. Spending the
	 * allowance on the attempt meant a typo cost the same as a comment: five rejections - too short,
	 * a filtered word, whichever - and an hour's silence, with nothing to show for it.
	 */
	fun check(bucket: Bucket, key: String, tier: TrustTier, commit: Boolean = true, cost: Int = 1): Decision {
		require(cost > 0) { "Rate-limit cost must be positive" }
		val requested = RequestedWindow("${bucket.name}:$key", bucket, bucket.limitFor(tier), cost)
		maybeEvict()
		return withRequestedLocks(listOf(requested)) { consume(listOf(requested), commit).first }
	}

	/**
	 * Coarse per-network bucket, roughly ten times a single user's allowance, to bound one network
	 * doing something silly. The key is a hash of the /24, held in memory only.
	 */
	fun checkNetwork(
		bucket: Bucket,
		networkKey: String,
		tier: TrustTier,
		commit: Boolean = true,
		cost: Int = 1,
	): Decision {
		require(cost > 0) { "Rate-limit cost must be positive" }
		val requested = RequestedWindow(
			"net:${bucket.name}:$networkKey",
			bucket,
			bucket.limitFor(tier) * NETWORK_MULTIPLIER,
			cost,
		)
		maybeEvict()
		return withRequestedLocks(listOf(requested)) { consume(listOf(requested), commit).first }
	}

	/** Atomically consumes both the identity and network allowance. */
	fun check(bucket: Bucket, key: String, networkKey: String, tier: TrustTier, cost: Int = 1): Decision {
		require(cost > 0) { "Rate-limit cost must be positive" }
		val requested = requested(bucket, key, networkKey, tier, cost)
		maybeEvict()
		return withRequestedLocks(requested) { consume(requested, commit = true).first }
	}

	/**
	 * Reserves several buckets as one operation. A rejected comment refunds the reservation; a stored
	 * comment commits it. Concurrent requests cannot pass a check without taking the same allowance.
	 */
	fun reserve(
		buckets: Collection<Bucket>,
		key: String,
		networkKey: String,
		tier: TrustTier,
	): ReservationResult {
		val requested = buckets.flatMap { requested(it, key, networkKey, tier) }
		maybeEvict()
		return withRequestedLocks(requested) {
			val (decision, starts) = consume(requested, commit = true)
			when (decision) {
				Decision.Allowed -> ReservationResult.Allowed(Reservation(starts))
				is Decision.Limited -> ReservationResult.Limited(decision)
			}
		}
	}

	private fun requested(bucket: Bucket, key: String, networkKey: String, tier: TrustTier, cost: Int = 1) = listOf(
		RequestedWindow("${bucket.name}:$key", bucket, bucket.limitFor(tier), cost),
		RequestedWindow(
			"net:${bucket.name}:$networkKey",
			bucket,
			bucket.limitFor(tier) * NETWORK_MULTIPLIER,
			cost,
		),
	)

	/** Called with every requested key stripe held. Returns starts so a reservation can be refunded safely. */
	private fun consume(requested: List<RequestedWindow>, commit: Boolean): Pair<Decision, Map<String, ConsumedWindow>> {
		val nowSecond = clock.instant().epochSecond

		for (request in requested) {
			val existing = activeWindow(request, nowSecond)
			val existingCount = existing?.count ?: 0
			if (request.cost > request.limit - existingCount) {
				return Decision.Limited(
					retryAfterSeconds = existing?.let {
						max(1, request.bucket.windowSeconds - (nowSecond - it.startEpochSecond))
					} ?: request.bucket.windowSeconds,
					bucket = request.bucket.name.lowercase(),
				) to emptyMap()
			}
		}
		if (!commit) return Decision.Allowed to emptyMap()

		// An expired entry can be removed between this check and commit, so it also takes the capacity
		// lock. Otherwise replacing that entry could race new insertions and exceed the hard bound.
		val mayAddKey = requested.any { activeWindow(it, nowSecond) == null }
		return if (mayAddKey) synchronized(capacityLock) {
			commit(requested, nowSecond)
		} else {
			commit(requested, nowSecond)
		}
	}

	/** New-key calls hold [capacityLock], making [maxEntries] a hard bound under concurrency. */
	private fun commit(
		requested: List<RequestedWindow>,
		nowSecond: Long,
	): Pair<Decision, Map<String, ConsumedWindow>> {
		val newKeys = requested.count {
			activeWindow(it, nowSecond) == null && !windows.containsKey(it.composite)
		}
		if (windows.size + newKeys > maxEntries) {
			// At capacity an attacker can continuously invent keys. Bound the expensive full-map
			// scan to once per second; periodic eviction still handles ordinary traffic.
			val next = nextCapacityEvictionSecond.get()
			if (nowSecond >= next && nextCapacityEvictionSecond.compareAndSet(next, nowSecond + 1)) {
				evictExpired(nowSecond)
			}
			if (windows.size + newKeys > maxEntries) {
				return Decision.Limited(
					OVER_CAPACITY_RETRY_SECONDS,
					requested.first().bucket.name.lowercase(),
				) to emptyMap()
			}
		}

		val starts = buildMap {
			requested.forEach { request ->
				val existing = activeWindow(request, nowSecond)
				val updated = if (existing == null) {
					Window(nowSecond, request.cost)
				} else {
					existing.copy(count = existing.count + request.cost)
				}
				windows[request.composite] = updated
				put(request.composite, ConsumedWindow(updated.startEpochSecond, request.cost))
			}
		}
		return Decision.Allowed to starts
	}

	private fun activeWindow(request: RequestedWindow, nowSecond: Long): Window? =
		windows[request.composite]?.takeIf {
			nowSecond - it.startEpochSecond < request.bucket.windowSeconds
		}

	/** Windows are tiny and self-expiring; this only keeps the map from growing without bound. */
	fun evictExpired() = evictExpired(clock.instant().epochSecond)

	private fun evictExpired(nowSecond: Long) {
		windows.entries.forEach { (key, window) ->
			val bucketName = key.substringAfter("net:").substringBefore(':')
			val bucket = Bucket.entries.firstOrNull { it.name == bucketName }
			if (bucket == null || nowSecond - window.startEpochSecond >= bucket.windowSeconds) {
				// Conditional removal cannot erase a replacement installed after this iterator observed it.
				windows.remove(key, window)
			}
		}
	}

	private fun maybeEvict() {
		if (operations.incrementAndGet() % EVICT_EVERY_OPERATIONS == 0L) evictExpired()
	}

	private fun <T> withRequestedLocks(requested: Collection<RequestedWindow>, block: () -> T): T =
		withCompositeLocks(requested.map(RequestedWindow::composite), block)

	private fun <T> withCompositeLocks(composites: Collection<String>, block: () -> T): T {
		val indices = composites
			.map { composite -> Math.floorMod(composite.hashCode(), locks.size) }
			.distinct()
			.sorted()
		return acquireLocks(indices, 0, block)
	}

	private fun <T> acquireLocks(indices: List<Int>, position: Int, block: () -> T): T {
		if (position == indices.size) return block()
		return synchronized(locks[indices[position]]) { acquireLocks(indices, position + 1, block) }
	}

	inner class Reservation internal constructor(private val starts: Map<String, ConsumedWindow>) {
		private val finished = AtomicBoolean(false)

		fun commit() {
			finished.set(true)
		}

		fun refund() {
			if (!finished.compareAndSet(false, true)) return
			withCompositeLocks(starts.keys) {
				starts.forEach { (key, consumed) ->
					windows.computeIfPresent(key) { _, window ->
						if (window.startEpochSecond != consumed.startEpochSecond) window
						else if (window.count <= consumed.cost) null
						else window.copy(count = window.count - consumed.cost)
					}
				}
			}
		}
	}

	sealed interface ReservationResult {
		data class Allowed(val reservation: Reservation) : ReservationResult
		data class Limited(val decision: Decision.Limited) : ReservationResult
	}

	sealed interface Decision {
		data object Allowed : Decision
		data class Limited(val retryAfterSeconds: Long, val bucket: String) : Decision
	}

	private companion object {
		const val NETWORK_MULTIPLIER = 10
		const val LOCK_STRIPES = 256
		const val DEFAULT_MAX_ENTRIES = 100_000
		const val EVICT_EVERY_OPERATIONS = 1024L
		const val OVER_CAPACITY_RETRY_SECONDS = 60L
	}
}
