package io.kotatsuredo.server

import io.kotatsuredo.server.ops.ResourceUsage
import io.kotatsuredo.server.ops.ServerMetrics
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The counters behind the health view.
 *
 * Worth testing directly because they are a process-wide object initialised in declaration order,
 * and the first version of the histogram was declared after the ring that reads it - which left
 * every counter throwing at startup and turned rate-limit refusals into 500s.
 */
class ServerMetricsTest {

	@BeforeTest
	fun clean() {
		ServerMetrics.reset()
	}

	@Test
	fun `counting a response does not depend on it being timed`() {
		ServerMetrics.recordResponse(200)
		ServerMetrics.recordResponse(503)

		val window = ServerMetrics.window(5)
		assertEquals(2, window.total)
		assertEquals(1, window.ok)
		assertEquals(1, window.serverError)
		assertNull(window.latencyP50Ms, "nothing was timed, so there is no median to report")
	}

	@Test
	fun `percentiles describe the requests that were timed`() {
		repeat(90) { ServerMetrics.recordResponse(200, durationMs = 30) }
		// A tenth of the traffic is slow, so the top 5% is squarely inside it.
		repeat(10) { ServerMetrics.recordResponse(200, durationMs = 3_000) }

		val window = ServerMetrics.window(5)
		val p50 = assertNotNull(window.latencyP50Ms)
		val p95 = assertNotNull(window.latencyP95Ms)
		assertEquals(50, p50, "the median request took 30ms, which is the 50ms bucket")
		assertTrue(p95 >= 1_600, "the slow tail must not be hidden by the fast majority: $p95")
	}

	/** A quota refusal is counted where it is decided, and must not need a timing to land. */
	@Test
	fun `refusals are counted by bucket and resource`() {
		ServerMetrics.recordRateLimited("RATINGS")
		ServerMetrics.recordOverloaded("catalogue")

		assertEquals(mapOf("RATINGS" to 1L), ServerMetrics.refusalsByBucket())
		assertEquals(mapOf("catalogue" to 1L), ServerMetrics.overloadsByResource())
	}

	@Test
	fun `the resource snapshot reports what the process is costing`() {
		val snapshot = ResourceUsage.snapshot()

		assertTrue(snapshot.heapUsedMb > 0, "heap: ${snapshot.heapUsedMb}")
		assertTrue(snapshot.heapMaxMb >= snapshot.heapUsedMb)
		assertTrue(snapshot.heapPercent in 0.0..1.0)
		assertTrue(snapshot.threads > 0)
		assertTrue(snapshot.processors > 0)
		// The optional figures are platform-dependent by design; they may be absent but never absurd.
		snapshot.cpuProcess?.let { assertTrue(it in 0.0..1.0, "cpu: $it") }
		snapshot.rssMb?.let { assertTrue(it > 0, "rss: $it") }
	}
}
