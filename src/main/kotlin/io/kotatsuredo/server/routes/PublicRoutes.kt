package io.kotatsuredo.server.routes

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.networkKey
import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.ops.ServerMetrics
import io.kotatsuredo.server.works.Genres
import io.kotatsuredo.server.works.WorkLimits
import io.kotatsuredo.server.works.WorkRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
	charts: Charts,
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

	/**
	 * What readers are rating and discussing right now.
	 *
	 * Ranked only on what people chose to publish - ratings and comments inside the window - and never
	 * on what they merely opened. Nothing adult is listed, whatever is asked. See
	 * [WorkRepository.chartCandidates].
	 *
	 * A genre outside the vocabulary is an empty chart rather than an error, as everywhere else an
	 * unknown label is dropped rather than refused: an app newer than this server keeps working.
	 *
	 * The app asks for one whole chart and narrows it down itself, so it never has to say what it looks for. A
	 * chart it already has costs a reply of no content, for as long as nothing has changed.
	 */
	get("/charts") {
		val parameters = call.request.queryParameters
		val window = ChartWindow.parse(parameters["window"]) ?: throw ApiException(ApiError.BadRequest("window"))
		val type = parameters["type"]
		if (type != null && type !in CHART_TYPES) throw ApiException(ApiError.BadRequest("type"))
		val limit = parameters["limit"]?.let { raw ->
			raw.toIntOrNull()?.takeIf { it in 1..MAX_CHART_LIMIT } ?: throw ApiException(ApiError.BadRequest("limit"))
		} ?: DEFAULT_CHART_LIMIT
		val rawGenre = parameters["genre"]
		val genre = rawGenre?.let(Genres::parse)
		call.enforceNetworkLimit(limiter, RateLimiter.Bucket.PUBLIC_LOOKUP)

		val response = if (rawGenre != null && (genre == null || genre in Charts.EXCLUDED_GENRES)) {
			charts.emptyFor(window)
		} else {
			charts.responseFor(ChartQuery(window, type, genre, limit))
		}
		call.response.header(HttpHeaders.CacheControl, "public, max-age=900")
		// The address says everything else about the answer, so when it was made is all a tag has to say. Looked for
		// inside what comes back, since a proxy that compresses an answer is free to reword its tag.
		call.response.header(HttpHeaders.ETag, "\"${response.generatedAt}\"")
		if (call.request.headers[HttpHeaders.IfNoneMatch]?.contains(response.generatedAt.toString()) == true) {
			call.respond(HttpStatusCode.NotModified)
		} else {
			call.respond(response)
		}
	}
}

private val CHART_TYPES = setOf("manga", "manhwa", "manhua")
private const val DEFAULT_CHART_LIMIT = 30
private const val MAX_CHART_LIMIT = 200

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
