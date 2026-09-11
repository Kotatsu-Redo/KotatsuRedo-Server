package io.kotatsuredo.server

import io.kotatsuredo.server.filter.FilterRules
import io.kotatsuredo.server.telemetry.TelemetryRetention
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The privacy notice, asserted against the schema it describes.
 *
 * `legal/PRIVACY.md` makes specific, checkable promises - no IP addresses anywhere, raw telemetry
 * deleted at seven days, blocked text at thirty, no email or password column. Those are the kind of
 * claim that is true on the day it is written and quietly false two migrations later, and the cost
 * of that is not a stale document: it is a privacy notice that misrepresents what the service does.
 *
 * So each one is a test. A migration that breaks a promise fails the build and forces the choice to
 * be deliberate - change the code, or change the notice.
 */
class PolicyClaimsTest {

	@BeforeTest
	fun setUp() = PostgresTestBase.requireDatabase()

	private fun columns(): List<Pair<String, String>> =
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT table_name, column_name FROM information_schema.columns
				WHERE table_schema = 'public'
				""".trimIndent(),
			).use { statement ->
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
				}
			}
		}

	/** "We never store your IP address. Not in a web-server log, not in the database, not anywhere." */
	@Test
	fun `no table has anywhere to put an IP address`() {
		val suspects = columns().filter { (_, column) ->
			column.contains("ip_") || column == "ip" || column.endsWith("_ip") ||
				column.contains("address") || column.contains("remote")
		}
		assertTrue(suspects.isEmpty(), "the schema grew somewhere to store an address: $suspects")
	}

	/** "No email address, no password, no phone number, no name." */
	@Test
	fun `the identity table holds no contact details or credentials`() {
		val user = columns().filter { it.first == "app_user" }.map { it.second }.toSet()
		// `nickname` is disclosed and allowed; a real name is not.
		val candidates = user - "nickname"
		listOf("email", "password", "phone", "name", "username").forEach { forbidden ->
			assertTrue(
				candidates.none { it.contains(forbidden) },
				"app_user gained a '$forbidden' column, which the privacy notice says does not exist",
			)
		}
		// What it does hold, and nothing more.
		assertEquals(
			setOf(
				"id", "secret_sha256", "nickname", "created_at", "last_seen_at",
				"is_banned", "ban_reason", "is_shadowbanned",
			),
			user,
			"app_user changed shape - check legal/PRIVACY.md still describes it",
		)
	}

	/** "The raw rows are deleted after 7 days." */
	@Test
	fun `raw telemetry retention is what the notice says`() {
		assertEquals(7L, TelemetryRetention.RETENTION_DAYS)
	}

	/** "That text is kept for 30 days and then erased." */
	@Test
	fun `blocked text retention is what the notice says`() {
		assertEquals(30L, FilterRules.CONTEXT_RETENTION_DAYS)
	}

	/**
	 * "Your comments are blanked. The empty rows stay so that replies other people wrote underneath
	 * them still have a thread to hang from."
	 *
	 * Which requires the author to be nullable and the key to be `SET NULL`. If it ever goes back to
	 * `CASCADE`, deleting an account deletes other people's replies - see `AccountDeletionTest`.
	 */
	@Test
	fun `deleting an account cannot cascade into other people's comments`() {
		val nullable = PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT is_nullable FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'comment' AND column_name = 'user_id'
				""".trimIndent(),
			).use { it.executeQuery().use { rows -> rows.next(); rows.getString(1) } }
		}
		assertEquals("YES", nullable, "comment.user_id must be nullable for a deletion to tombstone")

		val rule = PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT rc.delete_rule
				FROM information_schema.referential_constraints rc
				JOIN information_schema.key_column_usage k ON k.constraint_name = rc.constraint_name
				WHERE k.table_name = 'comment' AND k.column_name = 'user_id'
				""".trimIndent(),
			).use { it.executeQuery().use { rows -> rows.next(); rows.getString(1) } }
		}
		assertEquals("SET NULL", rule, "comment.user_id must not cascade on delete")
	}

	/** "a log the database itself will not let anyone edit or delete" - the content policy's claim. */
	@Test
	fun `the moderation log cannot be rewritten`() {
		val triggers = PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"SELECT event_manipulation FROM information_schema.triggers WHERE event_object_table = 'mod_action'",
			).use { statement ->
				statement.executeQuery().use { rows ->
					buildSet<String> { while (rows.next()) add(rows.getString(1)) }
				}
			}
		}
		assertTrue("UPDATE" in triggers, "mod_action has no trigger blocking UPDATE")
		assertTrue("DELETE" in triggers, "mod_action has no trigger blocking DELETE")
	}

	/**
	 * A policy nobody can open is not published.
	 *
	 * The documents are Markdown in `legal/` so their history is reviewable, and copied into the jar
	 * so the instance serves the text that was actually committed. A build that forgets the copy step
	 * leaves the server serving "this document is missing", which is worse than not linking to it.
	 */
	@Test
	fun `the policy documents are packaged with the server`() {
		listOf("TERMS", "PRIVACY", "CONTENT-POLICY").forEach { name ->
			val text = javaClass.getResourceAsStream("/legal/$name.md")?.bufferedReader()?.readText()
			assertTrue(!text.isNullOrBlank(), "legal/$name.md is not on the classpath")
			assertTrue(
				text!!.contains("Kotatsu-Redo community server"),
				"legal/$name.md does not look like the document it should be",
			)
		}
		assertTrue(
			!javaClass.getResourceAsStream("/rules.html")?.bufferedReader()?.readText().isNullOrBlank(),
			"rules.html is not on the classpath",
		)
	}

	/**
	 * "It never contains which manga you looked at, or any title, or any search you typed."
	 *
	 * The telemetry table is about sources, so it must have no column that could carry a work.
	 */
	@Test
	fun `telemetry cannot carry what anyone was reading`() {
		val probe = columns().filter { it.first == "source_probe_raw" }.map { it.second }.toSet()
		listOf("work", "manga", "title", "query", "search", "url").forEach { forbidden ->
			assertTrue(
				probe.none { it.contains(forbidden) },
				"source_probe_raw gained a '$forbidden' column; telemetry is about sources, not reading",
			)
		}
	}
}
