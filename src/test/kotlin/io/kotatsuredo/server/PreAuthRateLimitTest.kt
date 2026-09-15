package io.kotatsuredo.server

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.plugins.configurePreAuthRateLimit
import io.kotatsuredo.server.plugins.configureSerialization
import io.kotatsuredo.server.plugins.configureStatusPages
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class PreAuthRateLimitTest {
	@Test
	fun `the v1 perimeter rejects traffic before a route runs`() = testApplication {
		val limiter = RateLimiter()
		var routeCalls = 0
		application {
			configureSerialization()
			configureStatusPages()
			configurePreAuthRateLimit(limiter)
			routing {
				get("/v1/probe") {
					routeCalls++
					call.respondText("ok")
				}
			}
		}

		val allowance = RateLimiter.Bucket.PRE_AUTH.limitFor(TrustTier.NEW) * 10
		repeat(allowance) {
			assertEquals(HttpStatusCode.OK, client.get("/v1/probe").status)
		}
		assertEquals(HttpStatusCode.TooManyRequests, client.get("/v1/probe").status)
		assertEquals(allowance, routeCalls)
	}

	@Test
	fun `the admin api perimeter rejects traffic before a route runs`() = testApplication {
		val limiter = RateLimiter()
		var routeCalls = 0
		application {
			configureSerialization()
			configureStatusPages()
			configurePreAuthRateLimit(limiter)
			routing {
				get("/admin/api/probe") {
					routeCalls++
					call.respondText("ok")
				}
			}
		}

		val allowance = RateLimiter.Bucket.PRE_AUTH.limitFor(TrustTier.NEW) * 10
		repeat(allowance) {
			assertEquals(HttpStatusCode.OK, client.get("/admin/api/probe").status)
		}
		assertEquals(HttpStatusCode.TooManyRequests, client.get("/admin/api/probe").status)
		assertEquals(allowance, routeCalls)
	}
}
