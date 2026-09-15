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

		fun connect(config: DatabaseConfig, enforceQueryTimeouts: Boolean = true): Database {
			val hikari = HikariConfig().apply {
				jdbcUrl = config.url
				username = config.user
				password = config.password
				maximumPoolSize = config.maxPoolSize
				connectionTimeout = 5_000
				validationTimeout = 2_000
				maxLifetime = 30 * 60_000
				keepaliveTime = 2 * 60_000
				driverClassName = "org.postgresql.Driver"
				addDataSourceProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS.toString())
				addDataSourceProperty("tcpKeepAlive", "true")
				if (enforceQueryTimeouts) {
					// Pool checkout timeouts do not stop a query blocked on a lock or a dead socket.
					// PostgreSQL applies these to every runtime connection before it can serve work.
					addDataSourceProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS.toString())
					addDataSourceProperty(
						"options",
						"-c statement_timeout=$STATEMENT_TIMEOUT_MS " +
							"-c lock_timeout=$LOCK_TIMEOUT_MS " +
							"-c idle_in_transaction_session_timeout=$IDLE_TRANSACTION_TIMEOUT_MS",
					)
				}
				// Fail fast on a bad DSN rather than hanging a request thread later.
				initializationFailTimeout = 10_000
				poolName = "kotatsuredo"
			}
			return Database(HikariDataSource(hikari))
		}

		private const val CONNECT_TIMEOUT_SECONDS = 5
		private const val SOCKET_TIMEOUT_SECONDS = 30
		private const val STATEMENT_TIMEOUT_MS = 15_000
		private const val LOCK_TIMEOUT_MS = 3_000
		private const val IDLE_TRANSACTION_TIMEOUT_MS = 30_000
	}
}

/**
 * Migrations run in a one-shot process and Compose starts the API only after that process succeeds.
 * A server running against a schema it does not expect is worse than a server that is plainly down.
 */
fun DataSource.migrate() {
	val result = Flyway.configure()
		.dataSource(this)
		.locations("classpath:db/migration")
		.load()
		.migrate()
	log.info("Flyway applied {} migration(s), schema now at {}", result.migrationsExecuted, result.targetSchemaVersion)
}
