package io.kotatsuredo.server

import io.kotatsuredo.server.auth.bearerSecret
import io.kotatsuredo.server.auth.configureTrustedProxyHeaders
import io.kotatsuredo.server.auth.networkKey
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuthBoundaryTest {
	@Test
	fun `bearer credentials must contain at least 256 bits of generated material`() = testApplication {
		application {
			routing { get("/secret") { call.respondText(call.bearerSecret() ?: "missing") } }
		}

		assertEquals(
			"missing",
			client.get("/secret") { header(HttpHeaders.Authorization, "Bearer ${"a".repeat(31)}") }.body<String>(),
		)
		assertEquals(
			"a".repeat(32),
			client.get("/secret") { header(HttpHeaders.Authorization, "Bearer ${"a".repeat(32)}") }.body<String>(),
		)
	}

	@Test
	fun `forwarding headers are ignored unless a trusted proxy is configured`() = testApplication {
		application {
			configureTrustedProxyHeaders(false)
			routing { get("/network") { call.respondText(call.networkKey()) } }
		}

		val first = client.get("/network") { header("X-Forwarded-For", "203.0.113.1") }.body<String>()
		val second = client.get("/network") { header("X-Forwarded-For", "198.51.100.1") }.body<String>()
		assertEquals(first, second)
	}

	@Test
	fun `a configured proxy can supply the network address`() = testApplication {
		application {
			configureTrustedProxyHeaders(true)
			routing { get("/network") { call.respondText(call.networkKey()) } }
		}

		val first = client.get("/network") { header("X-Forwarded-For", "203.0.113.1") }.body<String>()
		val second = client.get("/network") { header("X-Forwarded-For", "198.51.100.1") }.body<String>()
		assertNotEquals(first, second)
	}
}
