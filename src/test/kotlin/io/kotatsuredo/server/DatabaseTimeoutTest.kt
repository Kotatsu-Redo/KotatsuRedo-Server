package io.kotatsuredo.server

import io.kotatsuredo.server.db.Database
import io.kotatsuredo.server.db.withBackgroundLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseTimeoutTest {

	@Test
	fun `runtime connections receive statement and lock timeouts`() {
		PostgresTestBase.requireDatabase()
		val database = Database.connect(
			DatabaseConfig(
				url = requireNotNull(System.getenv("TEST_DATABASE_URL")),
				user = requireNotNull(System.getenv("TEST_DATABASE_USER")),
				password = requireNotNull(System.getenv("TEST_DATABASE_PASSWORD")),
				maxPoolSize = 1,
			),
		)
		try {
			database.source.connection.use { connection ->
				assertEquals("15s", show(connection, "statement_timeout"))
				assertEquals("3s", show(connection, "lock_timeout"))
				assertEquals("30s", show(connection, "idle_in_transaction_session_timeout"))
			}
		} finally {
			database.close()
		}
	}

	/** The source scores froze because their weekly read could not finish inside a request's limits. */
	@Test
	fun `a background job gets longer limits that end with it`() {
		PostgresTestBase.requireDatabase()
		val database = Database.connect(
			DatabaseConfig(
				url = requireNotNull(System.getenv("TEST_DATABASE_URL")),
				user = requireNotNull(System.getenv("TEST_DATABASE_USER")),
				password = requireNotNull(System.getenv("TEST_DATABASE_PASSWORD")),
				maxPoolSize = 1,
			),
		)
		try {
			database.source.connection.use { connection ->
				val networkTimeout = connection.networkTimeout
				connection.withBackgroundLimits {
					assertEquals("5min", show(connection, "statement_timeout"))
					assertTrue(connection.networkTimeout > 300_000, "the socket must outlast the statement")
				}
				assertEquals("15s", show(connection, "statement_timeout"))
				assertEquals(networkTimeout, connection.networkTimeout)
				assertTrue(connection.autoCommit)

				assertFailsWith<IllegalStateException> {
					connection.withBackgroundLimits { error("the job failed") }
				}
				assertEquals("15s", show(connection, "statement_timeout"), "a failed job leaves nothing behind either")
				assertTrue(connection.autoCommit)
			}
		} finally {
			database.close()
		}
	}

	private fun show(connection: java.sql.Connection, setting: String): String =
		connection.createStatement().use { statement ->
			statement.executeQuery("SHOW $setting").use { rows -> rows.next(); rows.getString(1) }
		}
}
