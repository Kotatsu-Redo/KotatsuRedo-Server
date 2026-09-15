package io.kotatsuredo.server

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.identity.TrustTier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Trust earns more rope, never less.
 *
 * The built-in numbers are easy to keep straight; the operator-set ones are not, and an inverted
 * ladder is invisible from the outside - a brand new account simply gets more than a settled one.
 * This pins the defaults, and [RateLimiter.validate] warns about whatever the environment supplies.
 */
class RateLimiterLadderTest {

	@Test
	fun `every bucket gives a more trusted tier at least as much`() {
		val ladder = listOf(TrustTier.NEW, TrustTier.NORMAL, TrustTier.ESTABLISHED)
		for (bucket in RateLimiter.Bucket.entries) {
			ladder.zipWithNext { lower, higher ->
				assertTrue(
					bucket.limitFor(lower) <= bucket.limitFor(higher),
					"${bucket.name}: $lower gets ${bucket.limitFor(lower)} but $higher only " +
						"gets ${bucket.limitFor(higher)}",
				)
			}
		}
	}
}
