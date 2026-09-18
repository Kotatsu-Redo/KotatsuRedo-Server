package io.kotatsuredo.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The server never returns a user-facing sentence.
 *
 * The userbase spans fifteen languages and the server has no business holding translations, so every
 * rejection is a machine-readable code plus the data the client needs to render its own localized
 * string (see PLAN.md §3, "Errors are codes, not prose").
 *
 * The JSON discriminator is `error`, so [RateLimited] serializes as
 * `{"error":"rate_limited","retry_after_s":900,"limit":"comments_per_hour"}`.
 */
@Serializable
sealed class ApiError {

	abstract val status: HttpStatusCode

	/** A term matched the word filter. [term] is passed through untranslated - it is the user's own word. */
	@Serializable
	@SerialName("filter_blocked")
	data class FilterBlocked(
		val term: String,
		val tier: String,
		@SerialName("rules_url") val rulesUrl: String,
		/**
		 * The logged block. The rejection dialog's "this was wrong" posts it back, which is how a
		 * false positive reaches the panel from the person best placed to notice it (PLAN.md §6).
		 */
		@SerialName("block_id") val blockId: String? = null,
	) : ApiError() {
		override val status get() = HttpStatusCode.UnprocessableEntity
	}

	@Serializable
	@SerialName("rate_limited")
	data class RateLimited(
		@SerialName("retry_after_s") val retryAfterSeconds: Long,
		val limit: String,
	) : ApiError() {
		override val status get() = HttpStatusCode.TooManyRequests
	}

	/**
	 * The server is briefly out of room for this kind of work - not a quota, and nothing the caller
	 * did wrong.
	 *
	 * Deliberately distinct from [RateLimited] even though both are 429: a quota says "you have had
	 * your share this hour" and wants a long back-off, this says "try again in a moment". A client
	 * that cannot tell them apart has to treat the recoverable one like the permanent one, which is
	 * what made a busy catalogue look like a work with no community data at all.
	 */
	@Serializable
	@SerialName("overloaded")
	data class Overloaded(
		@SerialName("retry_after_s") val retryAfterSeconds: Long,
		val resource: String,
	) : ApiError() {
		override val status get() = HttpStatusCode.TooManyRequests
	}

	/** Two people have already exchanged their three rounds in this chain. */
	@Serializable
	@SerialName("chain_depth_exceeded")
	data object ChainDepthExceeded : ApiError() {
		override val status get() = HttpStatusCode.UnprocessableEntity
	}

	@Serializable
	@SerialName("too_short")
	data class TooShort(val min: Int) : ApiError() {
		override val status get() = HttpStatusCode.UnprocessableEntity
	}

	@Serializable
	@SerialName("banned")
	data object Banned : ApiError() {
		override val status get() = HttpStatusCode.Forbidden
	}

	/**
	 * No bearer secret, or one that resolves to nothing. Distinct from [Banned]: this means "call
	 * identity/hello first", whereas Banned is terminal and the app should say so.
	 */
	@Serializable
	@SerialName("unauthorized")
	data object Unauthorized : ApiError() {
		override val status get() = HttpStatusCode.Unauthorized
	}

	/**
	 * The work was merged into another. Clients cache `work_id` indefinitely, so every work-keyed
	 * endpoint has to be able to say "it moved" or those caches go stale forever (PLAN.md §5).
	 */
	@Serializable
	@SerialName("work_moved")
	data class WorkMoved(
		@SerialName("moved_to") val movedTo: String,
	) : ApiError() {
		override val status get() = HttpStatusCode.Conflict
	}

	/**
	 * Authenticated, but not allowed to do this. [required] says what was missing - an admin role, or
	 * a finished TOTP enrolment - so the panel can say something better than "no".
	 */
	@Serializable
	@SerialName("forbidden")
	data class Forbidden(val required: String) : ApiError() {
		override val status get() = HttpStatusCode.Forbidden
	}

	@Serializable
	@SerialName("not_found")
	data object NotFound : ApiError() {
		override val status get() = HttpStatusCode.NotFound
	}

	@Serializable
	@SerialName("bad_request")
	data class BadRequest(val field: String? = null) : ApiError() {
		override val status get() = HttpStatusCode.BadRequest
	}

	@Serializable
	@SerialName("internal_error")
	data object InternalError : ApiError() {
		override val status get() = HttpStatusCode.InternalServerError
	}
}

/** Throw from anywhere; the StatusPages plugin renders it with the right status. */
class ApiException(val error: ApiError) : RuntimeException(error::class.simpleName)
