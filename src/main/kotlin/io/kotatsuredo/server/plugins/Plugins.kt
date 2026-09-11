package io.kotatsuredo.server.plugins

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.ktor.http.HttpHeaders
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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
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
	install(ContentNegotiation) {
		json(ApiJson)
	}
}

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
		exception<Throwable> { call, cause ->
			// Log the class and message, never the request that caused it.
			log.error("Unhandled ${cause::class.simpleName} at ${call.request.path()}", cause)
			call.respond(ApiError.InternalError.status, ApiError.InternalError as ApiError)
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
