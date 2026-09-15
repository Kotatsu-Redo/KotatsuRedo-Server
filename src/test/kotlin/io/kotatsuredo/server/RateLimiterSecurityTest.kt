package io.kotatsuredo.server

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.identity.TrustTier
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RateLimiterSecurityTest {

	@Test
	fun `a refunded reservation does not consume allowance`() {
		val limiter = RateLimiter()
		val held = assertIs<RateLimiter.ReservationResult.Allowed>(
			limiter.reserve(listOf(RateLimiter.Bucket.COMMENTS), "user", "network", TrustTier.NEW),
		)
		held.reservation.refund()

		repeat(RateLimiter.Bucket.COMMENTS.limitFor(TrustTier.NEW)) {
			assertIs<RateLimiter.Decision.Allowed>(
				limiter.check(RateLimiter.Bucket.COMMENTS, "user", "network", TrustTier.NEW),
			)
		}
	}

	@Test
	fun `a failed multi bucket reservation consumes no partial allowance`() {
		val limiter = RateLimiter()
		repeat(RateLimiter.Bucket.THREAD_REPLIES.limitFor(TrustTier.NEW)) {
			assertIs<RateLimiter.Decision.Allowed>(
				limiter.check(RateLimiter.Bucket.THREAD_REPLIES, "user", "network", TrustTier.NEW),
			)
		}
		assertIs<RateLimiter.ReservationResult.Limited>(
			limiter.reserve(
				listOf(RateLimiter.Bucket.COMMENTS, RateLimiter.Bucket.THREAD_REPLIES),
				"user",
				"network",
				TrustTier.NEW,
			),
		)
		repeat(RateLimiter.Bucket.COMMENTS.limitFor(TrustTier.NEW)) {
			assertIs<RateLimiter.Decision.Allowed>(
				limiter.check(RateLimiter.Bucket.COMMENTS, "user", "network", TrustTier.NEW),
			)
		}
	}

	@Test
	fun `concurrent requests cannot exceed a bucket`() {
		val limiter = RateLimiter()
		val executor = Executors.newFixedThreadPool(8)
		try {
			val results = executor.invokeAll(
				List(40) {
					Callable {
						limiter.check(RateLimiter.Bucket.COMMENTS, "user", "network", TrustTier.NEW)
					}
				},
			)
			assertEquals(
				RateLimiter.Bucket.COMMENTS.limitFor(TrustTier.NEW),
				results.count { it.get() is RateLimiter.Decision.Allowed },
			)
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `new keys are rejected when the bounded map is full`() {
		val limiter = RateLimiter(maxEntries = 2)
		assertIs<RateLimiter.Decision.Allowed>(
			limiter.check(RateLimiter.Bucket.GENERAL, "first", "network-a", TrustTier.NEW),
		)
		assertIs<RateLimiter.Decision.Limited>(
			limiter.check(RateLimiter.Bucket.GENERAL, "second", "network-b", TrustTier.NEW),
		)
	}

	@Test
	fun `concurrent new keys cannot exceed the map capacity`() {
		val limiter = RateLimiter(maxEntries = 20)
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(16)
		try {
			val results = (1..40).map { index ->
				executor.submit(Callable {
					start.await()
					limiter.check(
						RateLimiter.Bucket.GENERAL,
						"user-$index",
						"network-$index",
						TrustTier.NEW,
					)
				})
			}
			start.countDown()
			assertEquals(10, results.count { it.get() is RateLimiter.Decision.Allowed })
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `a work batch consumes allowance by item count`() {
		val limiter = RateLimiter()
		val limit = RateLimiter.Bucket.WORK_MUTATION.limitFor(TrustTier.NEW)
		assertIs<RateLimiter.Decision.Allowed>(
			limiter.check(
				RateLimiter.Bucket.WORK_MUTATION,
				"user",
				"network",
				TrustTier.NEW,
				cost = limit - 1,
			),
		)
		assertIs<RateLimiter.Decision.Limited>(
			limiter.check(
				RateLimiter.Bucket.WORK_MUTATION,
				"user",
				"network",
				TrustTier.NEW,
				cost = 2,
			),
		)
		assertIs<RateLimiter.Decision.Allowed>(
			limiter.check(RateLimiter.Bucket.WORK_MUTATION, "user", "network", TrustTier.NEW),
		)
	}
}
