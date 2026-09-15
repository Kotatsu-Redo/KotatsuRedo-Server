package io.kotatsuredo.server

import io.kotatsuredo.server.plugins.configureSerialization
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestBodyLimitTest {

	@Test
	fun `payloads over 256 KiB are rejected before deserialization`() = testApplication {
		application {
			configureSerialization()
			routing {
				post("/limited") {
					call.receiveText()
					call.respondText("ok")
				}
			}
		}

		val response = client.post("/limited") { setBody("x".repeat(256 * 1024 + 1)) }
		assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
	}
}
