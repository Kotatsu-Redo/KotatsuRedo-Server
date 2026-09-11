package io.kotatsuredo.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotatsuredo.server.db.Database
import io.kotatsuredo.server.db.migrate
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Integration tests run against a real Postgres, migrated by the real Flyway migrations.
 *
 * Half of what M1 relies on is Postgres behaviour - `bytea` comparison semantics, the unique index on
 * `secret_sha256`, the `user_trust` view - which an in-memory database would either fake or fail
 * differently. So these tests need a database, and take one from the environment rather than starting
 * it themselves:
 *
 *   docker compose -f docker-compose.yml -f docker-compose.test.yml up -d postgres
 *   export TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/kotatsuredo
 *
 * CI supplies it as a service container. When it is absent the tests skip loudly rather than failing,
 * so `./gradlew build` still works on a machine with no database - see README.
 */
object PostgresTestBase {

	private val url: String? = System.getenv("TEST_DATABASE_URL")
	private val user: String = System.getenv("TEST_DATABASE_USER") ?: "kotatsuredo"
	private val password: String = System.getenv("TEST_DATABASE_PASSWORD") ?: "kotatsuredo"

	val isAvailable: Boolean get() = url != null

	/** Call from @BeforeTest so the whole class skips when no database is configured. */
	fun requireDatabase() = assumeTrue(
		isAvailable,
		"TEST_DATABASE_URL is not set - skipping integration tests (see README)",
	)

	val database: Database by lazy {
		val jdbcUrl = requireNotNull(url) { "TEST_DATABASE_URL is not set" }
		val hikari = HikariConfig().apply {
			this.jdbcUrl = jdbcUrl
			username = user
			password = this@PostgresTestBase.password
			driverClassName = "org.postgresql.Driver"
			maximumPoolSize = 4
		}
		Database(HikariDataSource(hikari)).also { it.source.migrate() }
	}

	/** Between tests, so each starts from an empty identity table without re-running migrations. */
	fun truncateAll() {
		database.source.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.execute(
					"TRUNCATE app_user, user_active_day, app_device, device_ban, ban_evasion_flag CASCADE",
				)
			}
		}
	}
}
