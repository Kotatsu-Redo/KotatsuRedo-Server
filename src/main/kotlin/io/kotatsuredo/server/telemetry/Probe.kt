package io.kotatsuredo.server.telemetry

/**
 * The operations worth probing. Page loads are sampled client-side at 10% because they happen on
 * every chapter - an order of magnitude more traffic than search or details for almost no extra
 * signal, since a source that serves pages reliably serves details reliably (PLAN.md §5).
 */
enum class ProbeOp(val code: Short) {
	SEARCH(0),
	DETAILS(1),
	PAGES(2),
	;

	companion object {
		fun parse(value: String): ProbeOp? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
	}
}

/**
 * Coarse regions, declared by the client from its locale and timezone and **never derived from an IP
 * address**.
 *
 * Stability is scored per region because a source geo-blocked in the EU must be demoted for EU users
 * and left alone everywhere else; without this, one region's blocks corrupt the global number
 * (PLAN.md §4). Coarse on purpose - finer buckets would start to identify people.
 */
enum class Region {
	EU,
	NA,
	LATAM,
	APAC,
	SOUTH_ASIA,
	MENA,
	AFRICA,
	OTHER,
	;

	companion object {
		/** Unknown values normalize to OTHER rather than being rejected: a client sending something we
		 *  do not recognise should still contribute to the global picture. */
		fun parse(value: String?): Region =
			entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: OTHER
	}
}

/**
 * One source's counts for one operation, already aggregated on the device over the reporting window.
 * The client uploads a batch of these once a day.
 */
data class Probe(
	val source: String,
	val op: ProbeOp,
	val ok: Int,
	val fail: Int,
	val empty: Int,
	val cfBlocked: Int,
	val latencyP50Ms: Int,
	val latencyP90Ms: Int,
) {
	val total: Int get() = ok + fail
}

object ProbeLimits {
	/** A device has a few hundred sources at most, times three operations. */
	const val MAX_BATCH = 512
	const val MAX_COUNT_PER_PROBE = 100_000
	const val MAX_LATENCY_MS = 120_000
	const val MAX_SOURCE_NAME = 64
}
