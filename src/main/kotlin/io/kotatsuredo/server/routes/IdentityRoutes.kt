package io.kotatsuredo.server.routes

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.bearerSecret
import io.kotatsuredo.server.auth.enforceLimit
import io.kotatsuredo.server.auth.requireCaller
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.identity.NicknameResult
import io.kotatsuredo.server.identity.Nicknames
import io.kotatsuredo.server.identity.TrustTier
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HelloRequest(
	/** ANDROID_ID. Sent only here, never on ordinary requests. */
	val ssaid: String,
	/** MediaDrm device id, absent on devices without a Widevine plugin. */
	@SerialName("drm_id") val drmId: String? = null,
	val nickname: String? = null,
)

/**
 * Note what is **not** here: `is_shadowbanned`.
 *
 * A shadowban only works while nothing signals it happened - the comment renders normally for its
 * author and is invisible to everyone else (PLAN.md §1). Returning the flag to the client would tell
 * the one person who must not know, so it never crosses the wire in any response.
 */
@Serializable
data class IdentityResponse(
	@SerialName("user_id") val userId: String,
	val nickname: String?,
	@SerialName("display_name") val displayName: String,
	val tier: Int,
)

fun Route.identityRoutes(
	identities: IdentityService,
	limiter: RateLimiter,
	rulesUrl: String = "/rules",
) = route("/identity") {

	/**
	 * Create-or-touch. The first call from an unknown secret creates the account; there is no
	 * separate registration step and no login (PLAN.md §1).
	 */
	post("/hello") {
		val secret = call.bearerSecret() ?: throw ApiException(ApiError.Unauthorized)
		val body = call.receive<HelloRequest>()
		if (body.ssaid.isBlank()) throw ApiException(ApiError.BadRequest("ssaid"))

		call.enforceLimit(limiter, RateLimiter.Bucket.HELLO, TrustTier.NEW, body.ssaid)

		when (val outcome = identities.hello(secret, DeviceIdentifiers(body.ssaid, body.drmId))) {
			HelloOutcome.DeviceBanned -> throw ApiException(ApiError.Banned)
			is HelloOutcome.Ok -> {
				body.nickname?.let { requested ->
					identities.setNickname(outcome.identity.id, requested).rejectIfInvalid(rulesUrl)
				}
				val identity = identities.get(outcome.identity.id) ?: outcome.identity
				call.respond(
					status = if (outcome.created) HttpStatusCode.Created else HttpStatusCode.OK,
					message = identity.toResponse(outcome.tier),
				)
			}
		}
	}

	get("/me") {
		val caller = call.requireCaller(identities)
		call.respond(caller.identity.toResponse(caller.tier))
	}

	post("/nickname") {
		val caller = call.requireCaller(identities)
		val body = call.receive<NicknameRequest>()
		identities.setNickname(caller.identity.id, body.nickname).rejectIfInvalid(rulesUrl)
		val updated = identities.get(caller.identity.id) ?: caller.identity
		call.respond(updated.toResponse(caller.tier))
	}

	/**
	 * "Delete everything about me". Ratings erased, comments tombstoned, user row gone. The secret
	 * is the proof, so no email loop is needed (PLAN.md §6).
	 */
	delete("/me") {
		val caller = call.requireCaller(identities)
		identities.deleteEverything(caller.identity.id)
		call.respond(HttpStatusCode.NoContent)
	}
}

@Serializable
data class NicknameRequest(val nickname: String)

private fun NicknameResult.rejectIfInvalid(rulesUrl: String) {
	when (this) {
		is NicknameResult.Valid -> Unit
		NicknameResult.TooShort -> throw ApiException(ApiError.TooShort(Nicknames.MIN_LENGTH))
		NicknameResult.TooLong -> throw ApiException(ApiError.BadRequest("nickname_too_long"))
		NicknameResult.Reserved -> throw ApiException(ApiError.BadRequest("nickname_reserved"))
		NicknameResult.InvalidCharacters -> throw ApiException(ApiError.BadRequest("nickname_invalid"))
		is NicknameResult.Blocked -> throw ApiException(
			ApiError.FilterBlocked(term, tier, rulesUrl, blockId?.toString()),
		)
	}
}

private fun io.kotatsuredo.server.identity.Identity.toResponse(tier: TrustTier) = IdentityResponse(
	userId = id,
	nickname = nickname,
	displayName = displayName,
	tier = tier.level,
)
