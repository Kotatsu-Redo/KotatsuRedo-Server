package io.kotatsuredo.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotatsuredo.server.DatabaseConfig
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource
import org.jetbrains.exposed.sql.Database as ExposedDatabase

private val log = LoggerFactory.getLogger("Database")

/**
 * The seam that lets `/v1/health` be tested without a live Postgres. Small on purpose: the route
 * needs one boolean, so it depends on one boolean rather than on a connection pool.
 */
fun interface HealthProbe {
	fun isHealthy(): Boolean
}

class Database(private val dataSource: HikariDataSource) : HealthProbe, AutoCloseable {

	val source: DataSource get() = dataSource

	/** Exposed binding over the same pool. Migrations remain Flyway's job, not Exposed's. */
	val exposed: ExposedDatabase by lazy { ExposedDatabase.connect(dataSource) }

	/** Cheap liveness probe for /v1/health. Deliberately not a query - a checkout is the thing that fails. */
	override fun isHealthy(): Boolean = runCatching {
		dataSource.connection.use { it.isValid(HEALTH_TIMEOUT_SECONDS) }
	}.getOrElse { error ->
		log.warn("Database health check failed", error)
		false
	}

	override fun close() = dataSource.close()

	companion object {

		private const val HEALTH_TIMEOUT_SECONDS = 2

		fun connect(config: DatabaseConfig): Database {
			val hikari = HikariConfig().apply {
				jdbcUrl = config.url
				username = config.user
				password = config.password
				maximumPoolSize = config.maxPoolSize
				driverClassName = "org.postgresql.Driver"
				// Fail fast on a bad DSN rather than hanging a request thread later.
				initializationFailTimeout = 10_000
				poolName = "kotatsuredo"
			}
			return Database(HikariDataSource(hikari))
		}
	}
}

/**
 * Migrations run at startup and the process refuses to serve if they fail. A server running against a
 * schema it does not expect is worse than a server that is plainly down.
 */
fun DataSource.migrate() {
	val result = Flyway.configure()
		.dataSource(this)
		.locations("classpath:db/migration")
		.load()
		.migrate()
	log.info("Flyway applied {} migration(s), schema now at {}", result.migrationsExecuted, result.targetSchemaVersion)
}
