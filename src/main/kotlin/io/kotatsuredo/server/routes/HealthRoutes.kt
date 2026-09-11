package io.kotatsuredo.server.routes

import io.kotatsuredo.server.db.HealthProbe
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val database: Boolean)

@Serializable
data class VersionResponse(val version: String, val api: String)

/**
 * `/v1/health` is what the external uptime check watches (PLAN.md §3 Ops). The app degrades silently
 * to "community features unavailable", so an outage is invisible from the user side and nobody will
 * report it - this endpoint is the only thing that will.
 */
fun Route.healthRoutes(database: HealthProbe, version: String) {
	get("/health") {
		val dbUp = database.isHealthy()
		call.respond(
			status = if (dbUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
			message = HealthResponse(status = if (dbUp) "ok" else "degraded", database = dbUp),
		)
	}
	get("/version") {
		call.respond(VersionResponse(version = version, api = "v1"))
	}
}
