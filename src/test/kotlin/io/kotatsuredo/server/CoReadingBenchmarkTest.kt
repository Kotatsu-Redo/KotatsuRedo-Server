package io.kotatsuredo.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotatsuredo.server.works.WorkRepository
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the daily co-reading rebuild stays inside the production statement timeout as the data
 * grows. Opt-in (`RUN_COREAD_BENCHMARK=1`): it seeds tens of thousands of accounts.
 *
 * It runs on a pool configured like production's - 15 s per statement, 3 s per lock, 30 s idle in a
 * transaction - so a slice that grew too big fails here the way it would fail on the server.
 */
class CoReadingBenchmarkTest {

	private fun seed(accounts: Int, works: Int, perAccount: Int) {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.execute(
					"TRUNCATE work, work_title, work_alias, work_alias_observation, work_coread, app_user, " +
						"user_active_day, app_device RESTART IDENTITY CASCADE",
				)
				statement.execute("INSERT INTO work (canonical_title) SELECT 'Work ' || g FROM generate_series(1, $works) g")
				statement.execute(
					"INSERT INTO app_user (id, secret_sha256) " +
						"SELECT 'bench-' || g, sha256(('secret-' || g)::bytea) FROM generate_series(1, $accounts) g",
				)
				// Skewed towards low ids, as readership is: a few works everybody opens, a long tail.
				statement.execute(
					"""
					INSERT INTO work_alias_observation (source, source_key, user_id, work_id, title_keys, updated_at)
					SELECT 'BENCH', 'k-' || w, 'bench-' || u, w, ARRAY['t'], now() - random() * interval '365 days'
					FROM (
						SELECT u, (1 + floor($works * power(random(), 2.5)))::bigint AS w
						FROM generate_series(1, $accounts) u, generate_series(1, $perAccount) k
					) picks
					ON CONFLICT DO NOTHING
					""".trimIndent(),
				)
				statement.execute("ANALYZE work_alias_observation")
			}
		}
	}

	private fun productionLikePool() = HikariDataSource(
		HikariConfig().apply {
			jdbcUrl = System.getenv("TEST_DATABASE_URL")
			username = System.getenv("TEST_DATABASE_USER") ?: "kotatsuredo"
			password = System.getenv("TEST_DATABASE_PASSWORD") ?: "kotatsuredo"
			maximumPoolSize = 2
			addDataSourceProperty(
				"options",
				"-c statement_timeout=15000 -c lock_timeout=3000 -c idle_in_transaction_session_timeout=30000",
			)
		},
	)

	@Test
	fun `the rebuild fits the statement timeout at scale`() {
		assumeTrue(PostgresTestBase.isAvailable && System.getenv("RUN_COREAD_BENCHMARK") == "1", "opt-in benchmark")
		PostgresTestBase.database // migrations
		val scales = listOf(
			Triple(5_000, 20_000, 40),
			Triple(20_000, 40_000, 60),
		)
		productionLikePool().use { pool ->
			val repository = WorkRepository(pool)
			for ((accounts, works, perAccount) in scales) {
				seed(accounts, works, perAccount)
				val started = System.nanoTime()
				val result = repository.refreshCoReading()
				val millis = (System.nanoTime() - started) / 1_000_000
				println(
					"co-reading: $accounts accounts x $perAccount works over $works -> " +
						"${result.accountWorks} rows, ${result.pairs} pairs, ${result.accountBuckets} bucket(s), " +
						"${result.pairBatches} batch(es), $millis ms total",
				)
				assertTrue(result.pairs > 0, "the skew should produce pairs at this scale")
			}
		}
	}
}
