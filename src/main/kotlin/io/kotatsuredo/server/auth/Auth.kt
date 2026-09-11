package io.kotatsuredo.server.auth

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.identity.Identity
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.identity.TrustTier
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.util.AttributeKey

private val CallerKey = AttributeKey<Caller>("kotatsuredo.caller")

data class Caller(val identity: Identity, val tier: TrustTier)

/**
 * Extracts `Authorization: Bearer <secret>` and resolves it to a user.
 *
 * Every endpoint requires this, including reads - there is no anonymous access path (PLAN.md §1).
 * A user who does not want an identity turns the feature off client-side and never calls us at all.
 *
 * The secret is never logged, never echoed in a response, and never stored: only its SHA-256 is.
 */
fun ApplicationCall.bearerSecret(): String? = request
	.header(HttpHeaders.Authorization)
	?.trim()
	?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
	?.substring(BEARER_PREFIX.length)
	?.trim()
	?.takeIf { it.isNotEmpty() }

/**
 * Resolves the caller or throws. Banned users are rejected here, which is what makes a ban remove
 * read access as well as write access - a deliberate consequence of identity being all-or-nothing.
 */
fun ApplicationCall.requireCaller(identities: IdentityService): Caller {
	attributes.getOrNull(CallerKey)?.let { return it }

	val secret = bearerSecret() ?: throw ApiException(ApiError.Unauthorized)
	val identity = identities.authenticate(secret) ?: throw ApiException(ApiError.Unauthorized)
	if (identity.isBanned) throw ApiException(ApiError.Banned)

	val caller = Caller(identity, identities.trustTier(identity.id))
	attributes.put(CallerKey, caller)
	return caller
}

/**
 * A coarse network key for the per-/24 bucket. Hashed immediately and held only in memory: the raw
 * address is never persisted and never written to a log line (PLAN.md §6).
 */
fun ApplicationCall.networkKey(): String {
	val forwarded = request.header("X-Forwarded-For")?.substringBefore(',')?.trim()
	val address = forwarded?.takeIf { it.isNotEmpty() } ?: request.local.remoteAddress
	val prefix = if (address.count { it == '.' } == 3) address.substringBeforeLast('.') else address
	return prefix.hashCode().toString()
}

fun ApplicationCall.enforceLimit(limiter: RateLimiter, bucket: RateLimiter.Bucket, tier: TrustTier, key: String) {
	when (val decision = limiter.check(bucket, key, tier)) {
		is RateLimiter.Decision.Limited ->
			throw ApiException(ApiError.RateLimited(decision.retryAfterSeconds, decision.bucket))

		RateLimiter.Decision.Allowed -> Unit
	}
	when (val decision = limiter.checkNetwork(bucket, networkKey(), tier)) {
		is RateLimiter.Decision.Limited ->
			throw ApiException(ApiError.RateLimited(decision.retryAfterSeconds, decision.bucket))

		RateLimiter.Decision.Allowed -> Unit
	}
}

private const val BEARER_PREFIX = "Bearer "
