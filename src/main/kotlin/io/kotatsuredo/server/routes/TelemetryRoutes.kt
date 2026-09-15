package io.kotatsuredo.server.routes

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.bearerSecret
import io.kotatsuredo.server.auth.enforceLimit
import io.kotatsuredo.server.auth.requireCaller
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.telemetry.Probe
import io.kotatsuredo.server.telemetry.ProbeLimits
import io.kotatsuredo.server.telemetry.ProbeOp
import io.kotatsuredo.server.telemetry.Region
import io.kotatsuredo.server.telemetry.TelemetryService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProbeBatchRequest(
	/** Declared by the client from locale and timezone. Never derived from an IP address. */
	val region: String,
	val probes: List<ProbeDto>,
)

@Serializable
data class ProbeDto(
	val source: String,
	val op: String,
	val ok: Int = 0,
	val fail: Int = 0,
	val empty: Int = 0,
	@SerialName("cf_blocked") val cfBlocked: Int = 0,
	@SerialName("p50_ms") val latencyP50Ms: Int = 0,
	@SerialName("p90_ms") val latencyP90Ms: Int = 0,
)

@Serializable
data class ProbeBatchAccepted(val accepted: Int)

fun Route.telemetryRoutes(
	identities: IdentityService,
	telemetry: TelemetryService,
	limiter: RateLimiter,
) = route("/telemetry") {

	/**
	 * Fire-and-forget: `202`, no meaningful body, and nothing here ever blocks the app.
	 *
	 * Authenticated so that trust weighting can discount hour-old identities, but the request is the
	 * only place the identity and the daily pseudonym exist together - only the pseudonym is stored
	 * (PLAN.md §6).
	 */
	post("/probes") {
		val caller = call.requireCaller(identities)
		val secret = call.bearerSecret() ?: throw ApiException(ApiError.Unauthorized)

		val body = call.receive<ProbeBatchRequest>()
		if (body.probes.size > ProbeLimits.MAX_BATCH) {
			throw ApiException(ApiError.BadRequest("too_many_probes"))
		}
		if (body.probes.asSequence().map { it.source.trim() }.distinct().count() > ProbeLimits.MAX_DISTINCT_SOURCES) {
			throw ApiException(ApiError.BadRequest("too_many_sources"))
		}
		// The unit of work is a probe row, not an HTTP request. Otherwise a maximum-size batch costs the
		// same as one probe and multiplies the effective ingestion allowance hundreds of times.
		call.enforceLimit(
			limiter,
			RateLimiter.Bucket.TELEMETRY,
			caller.tier,
			caller.identity.id,
			cost = maxOf(1, body.probes.size),
		)

		val probes = body.probes.mapNotNull { dto ->
			val op = ProbeOp.parse(dto.op) ?: return@mapNotNull null
			Probe(
				source = dto.source,
				op = op,
				ok = dto.ok,
				fail = dto.fail,
				empty = dto.empty,
				cfBlocked = dto.cfBlocked,
				latencyP50Ms = dto.latencyP50Ms,
				latencyP90Ms = dto.latencyP90Ms,
			)
		}

		val accepted = telemetry.record(
			secret = secret,
			tier = caller.tier,
			region = Region.parse(body.region),
			probes = probes,
		)
		call.respond(HttpStatusCode.Accepted, ProbeBatchAccepted(accepted))
	}
}
