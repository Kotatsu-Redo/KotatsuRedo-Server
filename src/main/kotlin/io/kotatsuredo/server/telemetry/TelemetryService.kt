package io.kotatsuredo.server.telemetry

import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.identity.dailyReporterId
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val log = LoggerFactory.getLogger("TelemetryService")
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

class TelemetryService(
	private val repository: TelemetryRepository,
	private val clock: Clock = Clock.systemUTC(),
) {

	/**
	 * The request is authenticated with the bearer secret so that trust weighting can tell a
	 * day-old identity from an established one - but **only the daily pseudonym is written**. The
	 * server sees both in the same request and persists just the one; the unlinkability is an
	 * operational guarantee, which is why this function is the only place holding both at once.
	 */
	fun record(secret: String, tier: TrustTier, region: Region, probes: List<Probe>): Int {
		val day = LocalDate.now(clock)
		val reporter = dailyReporterId(secret, day.format(DAY_FORMAT))
		val sanitized = probes.mapNotNull(::sanitize)
		if (sanitized.isEmpty()) return 0

		val written = repository.record(sanitized, day, region, reporter, tier.level)
		log.debug("Recorded {} probe rows for region {}", written, region)
		return sanitized.size
	}

	/**
	 * Clamps rather than rejects. A malformed batch from one device should not cost that device its
	 * whole upload, and every value here is a count whose only failure mode is being implausible.
	 */
	private fun sanitize(probe: Probe): Probe? {
		val source = probe.source.trim().take(ProbeLimits.MAX_SOURCE_NAME)
		if (source.isEmpty()) return null
		val ok = probe.ok.clampCount()
		val fail = probe.fail.clampCount()
		val empty = probe.empty.clampCount()
		val cfBlocked = probe.cfBlocked.clampCount()
		if (ok == 0 && fail == 0 && empty == 0 && cfBlocked == 0) return null

		return probe.copy(
			source = source,
			ok = ok,
			fail = fail,
			// `empty` counts "200 OK with zero results", which is a broken parser rather than a
			// failure - it cannot exceed the successes it is a subset of.
			empty = minOf(empty, ok),
			cfBlocked = minOf(cfBlocked, ok + fail),
			latencyP50Ms = probe.latencyP50Ms.coerceIn(0, ProbeLimits.MAX_LATENCY_MS),
			latencyP90Ms = probe.latencyP90Ms.coerceIn(0, ProbeLimits.MAX_LATENCY_MS),
		)
	}

	private fun Int.clampCount() = coerceIn(0, ProbeLimits.MAX_COUNT_PER_PROBE)
}
