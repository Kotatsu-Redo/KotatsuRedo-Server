package io.kotatsuredo.server

import io.kotatsuredo.server.db.Database
import kotlin.test.Test
import kotlin.test.assertEquals

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

	private fun show(connection: java.sql.Connection, setting: String): String =
		connection.createStatement().use { statement ->
			statement.executeQuery("SHOW $setting").use { rows -> rows.next(); rows.getString(1) }
		}
}
