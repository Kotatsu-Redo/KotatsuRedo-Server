package io.kotatsuredo.server.auth

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.identity.Identity
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.identity.TrustTier
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.util.AttributeKey
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64

private val CallerKey = AttributeKey<Caller>("kotatsuredo.caller")
private val TrustProxyHeadersKey = AttributeKey<Boolean>("kotatsuredo.trust-proxy-headers")

/** Forwarding headers are meaningful only when the deployment explicitly enables its reverse proxy. */
fun Application.configureTrustedProxyHeaders(enabled: Boolean) {
	attributes.put(TrustProxyHeadersKey, enabled)
}

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
	?.takeIf(::isValidBearerSecret)

/**
 * Resolves the caller or throws. Banned users are rejected here, which is what makes a ban remove
 * read access as well as write access - a deliberate consequence of identity being all-or-nothing.
 */
fun ApplicationCall.requireCaller(identities: IdentityService): Caller {
	attributes.getOrNull(CallerKey)?.let { return it }

	val secret = bearerSecret() ?: throw ApiException(ApiError.Unauthorized)
	val authenticated = identities.authenticateCaller(secret) ?: throw ApiException(ApiError.Unauthorized)
	val identity = authenticated.first
	if (identity.isBanned) throw ApiException(ApiError.Banned)

	val caller = Caller(identity, authenticated.second)
	attributes.put(CallerKey, caller)
	return caller
}

/**
 * A coarse network key for the per-/24 bucket. Hashed immediately and held only in memory: the raw
 * address is never persisted and never written to a log line (PLAN.md §6).
 */
fun ApplicationCall.networkKey(): String = networkKey(
	forwardedFor = request.header("X-Forwarded-For")
		.takeIf { application.attributes.getOrNull(TrustProxyHeadersKey) == true },
	remoteAddress = request.local.remoteAddress,
)

/**
 * The **last** entry of `X-Forwarded-For`, not the first.
 *
 * Caddy appends the address it saw to whatever arrived, so a client that sends its own
 * `X-Forwarded-For: 1.2.3.4` produces `1.2.3.4, <real address>`. Reading the first entry would let
 * anyone dodge the per-network bucket by rotating a header. Caddy is the only trusted hop and the
 * entry it added is the last one; everything to the left of it is client input.
 */
internal fun networkKey(forwardedFor: String?, remoteAddress: String): String {
	val address = forwardedFor?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() }
		?: remoteAddress
	val prefix = networkPrefix(address) ?: address.take(MAX_NETWORK_INPUT)
	return opaqueRateLimitKey(prefix)
}

private fun networkPrefix(address: String): String? {
	if (!isNumericAddress(address)) return null
	return runCatching { InetAddress.getByName(address) }.getOrNull()?.let { parsed ->
		when (parsed) {
			is Inet6Address -> parsed.address.take(8).joinToString(":") { "%02x".format(it) }
			else -> parsed.hostAddress.substringBeforeLast('.', parsed.hostAddress)
		}
	}
}

/** Rejects hostnames before InetAddress is called, so attacker input can never cause a DNS lookup. */
private fun isNumericAddress(address: String): Boolean {
	if (address.length !in 1..MAX_NETWORK_INPUT || address.any { it !in "0123456789abcdefABCDEF:." }) {
		return false
	}
	if (':' !in address) return isIpv4Literal(address)
	val dottedSuffix = address.substringAfterLast(':')
	return '.' !in address || isIpv4Literal(dottedSuffix)
}

private fun isIpv4Literal(address: String): Boolean {
	val octets = address.split('.')
	return octets.size == 4 && octets.all { octet ->
		octet.isNotEmpty() && octet.all(Char::isDigit) && octet.toIntOrNull() in 0..255
	}
}

private fun isValidBearerSecret(secret: String): Boolean {
	val byteLength = secret.toByteArray(Charsets.UTF_8).size
	return byteLength in MIN_BEARER_BYTES..MAX_BEARER_LENGTH && secret.none(Char::isISOControl)
}

/** A bounded opaque key for attacker-controlled identifiers retained by the in-memory limiter. */
fun opaqueRateLimitKey(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
	MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
)

/**
 * @param commit false to check the allowance without spending it - see [RateLimiter.check]. The
 *  caller then spends it with a second call once the work has actually been done.
 */
fun ApplicationCall.enforceLimit(
	limiter: RateLimiter,
	bucket: RateLimiter.Bucket,
	tier: TrustTier,
	key: String,
	commit: Boolean = true,
	cost: Int = 1,
) {
	val decision = if (commit) {
		limiter.check(bucket, key, networkKey(), tier, cost)
	} else {
		limiter.check(bucket, key, tier, commit = false, cost = cost)
	}
	when (decision) {
		is RateLimiter.Decision.Limited ->
			throw ApiException(ApiError.RateLimited(decision.retryAfterSeconds, decision.bucket))

		RateLimiter.Decision.Allowed -> Unit
	}
}

fun ApplicationCall.reserveLimits(
	limiter: RateLimiter,
	buckets: Collection<RateLimiter.Bucket>,
	tier: TrustTier,
	key: String,
): RateLimiter.Reservation = when (val result = limiter.reserve(buckets, key, networkKey(), tier)) {
	is RateLimiter.ReservationResult.Allowed -> result.reservation
	is RateLimiter.ReservationResult.Limited -> {
		val decision = result.decision
		throw ApiException(ApiError.RateLimited(decision.retryAfterSeconds, decision.bucket))
	}
}

private const val BEARER_PREFIX = "Bearer "
private const val MIN_BEARER_BYTES = 32
private const val MAX_BEARER_LENGTH = 512
private const val MAX_NETWORK_INPUT = 128
