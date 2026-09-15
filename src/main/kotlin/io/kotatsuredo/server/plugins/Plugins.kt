package io.kotatsuredo.server.plugins

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.networkKey
import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.routes.ADMIN_CSRF_HEADER
import io.kotatsuredo.server.routes.ADMIN_CSRF_VALUE
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.coroutines.CancellationException
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.server.response.respond
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID

private val log = KtorSimpleLogger("Application")

val ApiJson = Json {
	classDiscriminator = "error"
	encodeDefaults = true
	explicitNulls = false
	ignoreUnknownKeys = true
	prettyPrint = false
}

fun Application.configureSerialization() {
	install(RequestBodyLimit) {
		bodyLimit { MAX_REQUEST_BODY_BYTES }
	}
	install(ContentNegotiation) {
		json(ApiJson)
	}
}

/**
 * JDBC is synchronous. Moving the downstream call pipeline onto a bounded blocking dispatcher keeps
 * a slow query or a saturated Hikari pool from occupying Netty's event-loop threads.
 */
fun Application.configureBlockingCalls(parallelism: Int) {
	require(parallelism > 0)
	val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)
	intercept(ApplicationCallPipeline.Setup) {
		withContext(dispatcher) { proceed() }
	}
}

private const val MAX_REQUEST_BODY_BYTES = 256L * 1024L

/**
 * Request logging that cannot leak a credential.
 *
 * The bearer secret *is* the identity (PLAN.md §1), so a single log line containing an
 * `Authorization` header would hand out accounts. The format below is an allow-list of four fields -
 * method, path, status, duration - rather than a redaction pass over something richer, because an
 * allow-list cannot be defeated by a header nobody thought to redact.
 *
 * `LoggingRedactionTest` asserts this and will fail the build if it regresses.
 */
fun Application.configureLogging() {
	install(CallId) {
		generate { UUID.randomUUID().toString().take(8) }
		verify { it.isNotBlank() }
	}
	install(CallLogging) {
		level = Level.INFO
		callIdMdc("call-id")
		// Never interpolate headers, query strings with tokens, or bodies into this.
		format { call ->
			val status = call.response.status()?.value ?: 0
			"${call.request.httpMethod.value} ${call.request.path()} -> $status"
		}
	}
	install(DefaultHeaders) {
		header("X-Content-Type-Options", "nosniff")
	}
}

fun Application.configureStatusPages() {
	install(StatusPages) {
		exception<ApiException> { call, cause ->
			call.respond(cause.error.status, cause.error as ApiError)
		}
		// A body that will not deserialize is the caller's mistake, not ours. Without this it falls
		// through to the handler below and comes back as `internal_error`, which sends whoever is
		// writing a client looking at the wrong end of the problem.
		exception<BadRequestException> { call, _ ->
			call.respond(HttpStatusCode.BadRequest, ApiError.BadRequest() as ApiError)
		}
		exception<CancellationException> { _, cause -> throw cause }
		exception<Exception> { call, cause ->
			// Log the class and message, never the request that caused it.
			log.error("Unhandled {} at {}", cause::class.qualifiedName, call.request.path(), cause)
			call.respond(ApiError.InternalError.status, ApiError.InternalError as ApiError)
		}
	}
}

/**
 * Rejects abusive traffic before bearer authentication reaches PostgreSQL.
 *
 * Identity-specific limits necessarily run after authentication; this coarse network ceiling is the
 * perimeter that also covers random or malformed credentials. It intentionally covers health too:
 * that endpoint checks out a connection and otherwise provides an unauthenticated pool-exhaustion
 * path of its own.
 */
fun Application.configurePreAuthRateLimit(limiter: RateLimiter) {
	intercept(ApplicationCallPipeline.Plugins) {
		val path = call.request.path()
		val protectedApi = path == "/v1" || path.startsWith("/v1/") ||
			path == "/admin/api" || path.startsWith("/admin/api/")
		if (!protectedApi) return@intercept
		when (
			val decision = limiter.checkNetwork(
				RateLimiter.Bucket.PRE_AUTH,
				call.networkKey(),
				TrustTier.NEW,
			)
		) {
			is RateLimiter.Decision.Limited ->
				throw ApiException(ApiError.RateLimited(decision.retryAfterSeconds, decision.bucket))

			RateLimiter.Decision.Allowed -> Unit
		}
	}
}

/**
 * Browser hardening for the moderation panel.
 *
 * Set here rather than in the reverse proxy so it holds on a local run too - a header that only
 * exists in one deployment's Caddyfile is a header nobody tests.
 *
 * `script-src 'self'` is the one that matters: the panel loads its script from a file, so an
 * injected `<script>` will not run even if something gets past the escaping. Inline *styles* are
 * still allowed, because the markup uses a handful of them and a CSS injection on a page with no
 * secrets in the DOM is not worth the churn.
 */
fun Application.configurePanelHeaders() {
	intercept(ApplicationCallPipeline.Plugins) {
		if (!call.request.path().startsWith("/admin")) return@intercept
		call.response.header("Content-Security-Policy", PANEL_CSP)
		// Belt and braces with frame-ancestors, for anything that predates CSP: a framed panel is a
		// moderator tricked into clicking "ban".
		call.response.header("X-Frame-Options", "DENY")
		call.response.header("X-Robots-Tag", "noindex, nofollow")
		// Queue contents are moderation data and have no business in a shared cache or a back button.
		call.response.header(HttpHeaders.CacheControl, "no-store")
	}
}

/** Require a non-simple request header on every admin mutation, including login. */
fun Application.configureAdminCsrf() {
	intercept(ApplicationCallPipeline.Plugins) {
		if (!call.request.path().startsWith("/admin/api/")) return@intercept
		if (call.request.httpMethod == HttpMethod.Get) return@intercept
		// An HTML form cannot set this. Cross-origin scripts require a CORS preflight, which the
		// server does not grant, so a sibling origin cannot ride the SameSite session cookie.
		if (call.request.headers[ADMIN_CSRF_HEADER] != ADMIN_CSRF_VALUE) {
			throw ApiException(ApiError.Forbidden("csrf"))
		}
	}
}

private val PANEL_CSP = listOf(
	"default-src 'none'",
	"script-src 'self'",
	"style-src 'self' 'unsafe-inline'",
	"img-src 'self' data:",
	"connect-src 'self'",
	"frame-ancestors 'none'",
	"base-uri 'none'",
	"form-action 'none'",
).joinToString("; ")
