package io.kotatsuredo.server

import io.kotatsuredo.server.db.HealthProbe
import io.kotatsuredo.server.scoring.ScoringRepository
import io.kotatsuredo.server.scoring.ScoringService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
			connection.createStatement().use {
				it.execute("TRUNCATE source_probe_raw, source_score, source_score_op")
			}
		}
	}

	@Test
	fun `the blob carries per-operation stability beside the blended score`() = testApplication {
		val repository = ScoringRepository(PostgresTestBase.database.source)
		application {
			module(health = HealthProbe { true }, scoring = ScoringService(repository))
		}
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO source_probe_raw
					(source, day, region, op, reporter_day, tier, ok, fail, empty, cf_blocked,
					 latency_p50_ms, latency_p90_ms)
				VALUES ('PAPER_TIGER', CURRENT_DATE, 'EU', 0, 'reporter-1', 2, 200, 1, 0, 0, 300, 600),
				       ('PAPER_TIGER', CURRENT_DATE, 'EU', 2, 'reporter-1', 2, 1, 200, 0, 0, 300, 600)
				""".trimIndent(),
			).use { it.executeUpdate() }
		}
		ScoringService(repository).recompute()

		val body = client.get("/v1/sources/scores?region=EU").bodyAsText()

		assertTrue("\"ops\"" in body, "the response should carry per-operation scores: $body")
		assertTrue("\"search\"" in body && "\"pages\"" in body, "both probed operations: $body")
		assertTrue("median_stability_by_op" in body, "and the per-operation prior: $body")
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
