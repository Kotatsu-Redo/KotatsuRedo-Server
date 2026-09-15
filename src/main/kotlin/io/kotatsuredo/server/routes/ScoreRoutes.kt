package io.kotatsuredo.server.routes

import io.kotatsuredo.server.scoring.ScoringService
import io.kotatsuredo.server.telemetry.Region
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

@Serializable
data class SourceScoreDto(
	val source: String,
	val stability: Double,
	val popularity: Double,
	val composite: Double,
	/** Reporter-days. Below the client's threshold, use [ScoresResponse.medianComposite] instead. */
	val samples: Int,
)

@Serializable
data class ScoresResponse(
	val region: String,
	@SerialName("generated_at") val generatedAt: String?,
	/** The optimistic prior for under-sampled sources - see ScoreSnapshot. */
	@SerialName("median_composite") val medianComposite: Double,
	@SerialName("median_stability") val medianStability: Double,
	val sources: List<SourceScoreDto>,
)

/**
 * One cacheable blob rather than per-source lookups: about 1,200 sources of a few floats each, which
 * is roughly 40 KB gzipped and something the app fetches once a day (PLAN.md §3).
 *
 * The ETag is the last recomputation time, so a client that polls daily while scores are unchanged
 * gets a 304 and transfers nothing.
 */
fun Route.scoreRoutes(
	scoring: ScoringService,
) = route("/sources") {

	get("/scores") {
		val region = Region.parse(call.request.queryParameters["region"])
		val snapshot = scoring.scores(region.name)

		val etag = snapshot.generatedAt
			?.format(DateTimeFormatter.ISO_INSTANT)
			?.let { "\"$it\"" }

		if (etag != null && call.request.header(HttpHeaders.IfNoneMatch) == etag) {
			call.respond(HttpStatusCode.NotModified)
			return@get
		}

		etag?.let { call.response.header(HttpHeaders.ETag, it) }
		// Scores move on a scale of days; a stale hour costs nothing and saves the round trip.
		call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")

		call.respond(
			ScoresResponse(
				region = snapshot.region,
				generatedAt = snapshot.generatedAt?.format(DateTimeFormatter.ISO_INSTANT),
				medianComposite = snapshot.medianComposite,
				medianStability = snapshot.medianStability,
				sources = snapshot.scores.map {
					SourceScoreDto(
						source = it.source,
						stability = it.stability,
						popularity = it.popularity,
						composite = it.composite,
						samples = it.sampleSize,
					)
				},
			),
		)
	}
}
