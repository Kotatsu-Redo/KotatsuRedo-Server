package io.kotatsuredo.server.routes

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.enforceLimit
import io.kotatsuredo.server.auth.requireCaller
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.ratings.RatingAggregate
import io.kotatsuredo.server.ratings.RatingScale
import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.works.WorkRepository
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RatingResponse(
	@SerialName("work_id") val workId: String,
	val count: Int,
	/** Display average in stars, 0-5. */
	val average: Double,
	/** What ranking uses. Never sort on [average]. */
	val bayesian: Double,
	/** Five buckets, one per whole star. */
	val histogram: List<Int>,
	/** This caller's own rating in stars, or null. */
	val mine: Double? = null,
)

@Serializable
data class SetRatingRequest(
	/** Half-star steps: 2 = one star, 10 = five. */
	val value: Int,
)

/** Stored values are half-stars, so everything user-facing is halved back into 0-5 stars here. */
private fun RatingAggregate.toResponse(mine: Int?) = RatingResponse(
	workId = workId.toString(),
	count = count,
	average = if (count == 0) 0.0 else mean / 2.0,
	bayesian = if (count == 0) 0.0 else bayesian / 2.0,
	histogram = histogram,
	mine = mine?.let { RatingScale.stars(it) },
)

fun Route.ratingRoutes(
	identities: IdentityService,
	ratings: RatingService,
	works: WorkRepository,
	limiter: RateLimiter,
) = route("/works/{id}/rating") {

	get {
		val caller = call.requireCaller(identities)
		call.enforceLimit(limiter, RateLimiter.Bucket.GENERAL, caller.tier, caller.identity.id)
		val workId = call.resolveWorkId(works)
		call.respond(ratings.aggregate(workId).toResponse(ratings.myRating(workId, caller.identity.id)))
	}

	put {
		val caller = call.requireCaller(identities)
		call.enforceLimit(limiter, RateLimiter.Bucket.RATINGS, caller.tier, caller.identity.id)

		val workId = call.resolveWorkId(works)
		val body = call.receive<SetRatingRequest>()
		if (!RatingScale.isValid(body.value)) throw ApiException(ApiError.BadRequest("value"))

		val aggregate = ratings.rate(workId, caller.identity.id, body.value)
		call.respond(aggregate.toResponse(body.value))
	}

	delete {
		val caller = call.requireCaller(identities)
		call.enforceLimit(limiter, RateLimiter.Bucket.RATINGS, caller.tier, caller.identity.id)
		val workId = call.resolveWorkId(works)
		call.respond(ratings.clear(workId, caller.identity.id).toResponse(null))
	}
}

/**
 * Resolves the path id, following a merge if the client is holding one that has since been merged
 * away.
 *
 * Clients cache `work_id` indefinitely, so without this a merged work would leave those caches
 * pointing at a dead id forever, silently showing no ratings (PLAN.md §5).
 */
internal fun io.ktor.server.application.ApplicationCall.resolveWorkId(works: WorkRepository): Long {
	val raw = parameters["id"]?.toLongOrNull() ?: throw ApiException(ApiError.BadRequest("id"))
	if (works.metadataOf(raw) != null) return raw
	works.mergedInto(raw)?.let { throw ApiException(ApiError.WorkMoved(it.toString())) }
	throw ApiException(ApiError.NotFound)
}
