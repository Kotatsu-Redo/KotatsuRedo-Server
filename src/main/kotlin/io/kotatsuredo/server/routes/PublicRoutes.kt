package io.kotatsuredo.server.routes

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.networkKey
import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.ops.ServerMetrics
import io.kotatsuredo.server.works.WorkLimits
import io.kotatsuredo.server.works.WorkRepository
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Lookups that need no account.
 *
 * For the app with the community features off - which is most of them - and with suggestions on,
 * which is now the default. The rule for everything here is that the request leaves nothing behind:
 * no account is made, nothing is written, and no credential is read even if one is sent.
 */
fun Route.publicRoutes(
	works: WorkRepository,
	similarWorks: SimilarWorks,
	limiter: RateLimiter,
) = route("/public") {

	/**
	 * Works like the one at `key` on `source`.
	 *
	 * The same answer as `/v1/works/{id}/similar`, reached by the entry rather than the server's id,
	 * since a device without an account has never been told any ids. Only for entries several
	 * accounts have established: the server will not guess, and an entry only one person has opened
	 * says something about that person rather than the work.
	 *
	 * The query string never reaches a log: request lines carry the path alone.
	 */
	get("/similar") {
		val ref = FingerprintRef(
			source = call.request.queryParameters["source"].orEmpty(),
			key = call.request.queryParameters["key"].orEmpty(),
		)
		if (!ref.isValid() || ref.source in WorkLimits.NON_NETWORK_SOURCES) {
			throw ApiException(ApiError.BadRequest("source_key"))
		}
		call.enforceNetworkLimit(limiter, RateLimiter.Bucket.PUBLIC_LOOKUP)

		val stored = works.corroboratedWorkFor(ref.source, ref.key) ?: throw ApiException(ApiError.NotFound)
		val workId = works.mergedInto(stored) ?: stored
		if (works.metadataOf(workId) == null) throw ApiException(ApiError.NotFound)

		// Public: the answer is about the work and the same for everyone, so any cache may keep it.
		call.response.header(HttpHeaders.CacheControl, "public, max-age=21600")
		call.respond(similarWorks.responseFor(workId))
	}
}

/** The per-network allowance alone, for routes with no account to charge. */
private fun ApplicationCall.enforceNetworkLimit(limiter: RateLimiter, bucket: RateLimiter.Bucket) {
	when (val decision = limiter.checkNetwork(bucket, networkKey(), TrustTier.NEW)) {
		is RateLimiter.Decision.Limited -> {
			ServerMetrics.recordRateLimited(decision.bucket)
			throw ApiException(ApiError.RateLimited(decision.retryAfterSeconds, decision.bucket))
		}

		RateLimiter.Decision.Allowed -> Unit
	}
}
