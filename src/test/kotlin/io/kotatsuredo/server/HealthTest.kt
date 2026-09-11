package io.kotatsuredo.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {

	@Test
	fun `health reports ok when the database is reachable`() = testApplication {
		application { module({ true }) }

		val response = client.get("/v1/health")

		assertEquals(HttpStatusCode.OK, response.status)
		assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
	}

	/**
	 * A degraded database must surface as 503 rather than 200, or the uptime check watching this
	 * endpoint (PLAN.md §3 Ops) would report a half-dead server as healthy.
	 */
	@Test
	fun `health reports 503 when the database is unreachable`() = testApplication {
		application { module({ false }) }

		val response = client.get("/v1/health")

		assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
		assertTrue(response.bodyAsText().contains("\"database\":false"))
	}

	@Test
	fun `version endpoint reports the api version`() = testApplication {
		application { module({ true }) }

		val body = client.get("/v1/version").bodyAsText()

		assertTrue(body.contains("\"api\":\"v1\""), "unexpected body: $body")
	}
}
