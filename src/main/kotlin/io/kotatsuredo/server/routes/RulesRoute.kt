package io.kotatsuredo.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The rules and the policies, served by the instance itself.
 *
 * Not links to GitHub: the rejection dialog points here, so these have to be versioned with the
 * deployment and have to work when GitHub is unreachable. Self-hosters get their own copy for free,
 * and the app can cache them (PLAN.md §6).
 *
 * The policy documents are Markdown in `legal/` because that is what makes a change to a privacy
 * notice reviewable as a diff, and they are copied into the jar at build time so the running server
 * serves exactly the text that was committed.
 *
 * **English only**, which is a known limitation rather than an oversight: a Vietnamese user whose
 * comment was just blocked is sent to a page they may not read, so the *rejection message* carries
 * the weight and that one is localised. If one translation is ever worth doing, do the language with
 * the highest block rate first - which the panel's per-language stats will tell you.
 */
fun Route.rulesRoute() {
	get("/rules") {
		call.servePage("/rules.html", null, "Community rules")
	}
	get("/terms") {
		call.servePage("/legal/TERMS.md", Markdown::render, "Terms of service")
	}
	get("/privacy") {
		call.servePage("/legal/PRIVACY.md", Markdown::render, "Privacy notice")
	}
	get("/content-policy") {
		call.servePage("/legal/CONTENT-POLICY.md", Markdown::render, "Content policy")
	}
}

private suspend fun io.ktor.server.application.ApplicationCall.servePage(
	resource: String,
	render: ((String, String) -> String)?,
	title: String,
) {
	val raw = RulesRoute::class.java.getResourceAsStream(resource)?.bufferedReader()?.readText()
	val body = when {
		raw == null -> missing(title)
		render == null -> raw
		else -> render(raw, title)
	}
	// These change rarely and the app caches them; an hour of staleness costs nothing.
	response.header(HttpHeaders.CacheControl, "public, max-age=3600")
	respondText(body, ContentType.Text.Html)
}

private object RulesRoute

private fun missing(title: String) =
    "<!doctype html><title>$title</title><p>This document is missing from this deployment.</p>"
