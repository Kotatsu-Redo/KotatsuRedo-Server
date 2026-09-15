package io.kotatsuredo.server

import io.kotatsuredo.server.db.HealthProbe
import io.kotatsuredo.server.scoring.ScoringRepository
import io.kotatsuredo.server.scoring.ScoringService
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreRoutesTest {

	@BeforeTest
	fun requireDatabase() {
		PostgresTestBase.requireDatabase()
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { it.execute("TRUNCATE source_score") }
		}
	}

	@Test
	fun `source scores are public and explicitly cacheable`() = testApplication {
		application {
			module(
				health = HealthProbe { true },
				scoring = ScoringService(ScoringRepository(PostgresTestBase.database.source)),
			)
		}

		val response = client.get("/v1/sources/scores?region=EU")

		assertEquals(HttpStatusCode.OK, response.status)
		assertTrue(response.headers[HttpHeaders.CacheControl]?.contains("public") == true)
	}
}
