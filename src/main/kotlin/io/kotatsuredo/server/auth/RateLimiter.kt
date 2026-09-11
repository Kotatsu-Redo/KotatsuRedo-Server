package io.kotatsuredo.server.auth

import io.kotatsuredo.server.identity.TrustTier
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
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
class RateLimiter(private val clock: Clock = Clock.systemUTC()) {

	enum class Bucket(val limit: Map<TrustTier, Int>, val windowSeconds: Long) {
		COMMENTS(mapOf(TrustTier.NEW to 5, TrustTier.NORMAL to 20, TrustTier.ESTABLISHED to 40), 3600),
		COMMENTS_DAILY(mapOf(TrustTier.NEW to 15, TrustTier.NORMAL to 60, TrustTier.ESTABLISHED to 150), 86_400),
		THREAD_REPLIES(mapOf(TrustTier.NEW to 5, TrustTier.NORMAL to 10, TrustTier.ESTABLISHED to 15), 3600),
		VOTES(mapOf(TrustTier.NEW to 60, TrustTier.NORMAL to 200, TrustTier.ESTABLISHED to 400), 3600),
		RATINGS(mapOf(TrustTier.NEW to 30, TrustTier.NORMAL to 100, TrustTier.ESTABLISHED to 200), 3600),
		HELLO(mapOf(TrustTier.NEW to 5, TrustTier.NORMAL to 5, TrustTier.ESTABLISHED to 5), 3600),
		GENERAL(mapOf(TrustTier.NEW to 300, TrustTier.NORMAL to 300, TrustTier.ESTABLISHED to 300), 60),
		;

		fun limitFor(tier: TrustTier): Int = limit.getValue(tier)
	}

	private data class Window(val startEpochSecond: Long, val count: Int)

	private val windows = ConcurrentHashMap<String, Window>()

	fun check(bucket: Bucket, key: String, tier: TrustTier): Decision {
		val limit = bucket.limitFor(tier)
		val nowSecond = clock.instant().epochSecond
		val composite = "${bucket.name}:$key"

		val updated = windows.compute(composite) { _, existing ->
			if (existing == null || nowSecond - existing.startEpochSecond >= bucket.windowSeconds) {
				Window(nowSecond, 1)
			} else {
				existing.copy(count = existing.count + 1)
			}
		}!!

		return if (updated.count <= limit) {
			Decision.Allowed
		} else {
			val elapsed = nowSecond - updated.startEpochSecond
			Decision.Limited(
				retryAfterSeconds = max(1, bucket.windowSeconds - elapsed),
				bucket = bucket.name.lowercase(),
			)
		}
	}

	/**
	 * Coarse per-network bucket, roughly ten times a single user's allowance, to bound one network
	 * doing something silly. The key is a hash of the /24, held in memory only.
	 */
	fun checkNetwork(bucket: Bucket, networkKey: String, tier: TrustTier): Decision {
		val limit = bucket.limitFor(tier) * NETWORK_MULTIPLIER
		val nowSecond = clock.instant().epochSecond
		val composite = "net:${bucket.name}:$networkKey"
		val updated = windows.compute(composite) { _, existing ->
			if (existing == null || nowSecond - existing.startEpochSecond >= bucket.windowSeconds) {
				Window(nowSecond, 1)
			} else {
				existing.copy(count = existing.count + 1)
			}
		}!!
		return if (updated.count <= limit) {
			Decision.Allowed
		} else {
			Decision.Limited(
				retryAfterSeconds = max(1, bucket.windowSeconds - (nowSecond - updated.startEpochSecond)),
				bucket = bucket.name.lowercase(),
			)
		}
	}

	/** Windows are tiny and self-expiring; this only keeps the map from growing without bound. */
	fun evictExpired() {
		val nowSecond = clock.instant().epochSecond
		windows.entries.removeIf { (key, window) ->
			val bucketName = key.substringAfter("net:").substringBefore(':')
			val bucket = Bucket.entries.firstOrNull { it.name == bucketName } ?: return@removeIf true
			nowSecond - window.startEpochSecond >= bucket.windowSeconds
		}
	}

	sealed interface Decision {
		data object Allowed : Decision
		data class Limited(val retryAfterSeconds: Long, val bucket: String) : Decision
	}

	private companion object {
		const val NETWORK_MULTIPLIER = 10
	}
}
