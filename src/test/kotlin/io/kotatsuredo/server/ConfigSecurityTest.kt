package io.kotatsuredo.server

import kotlin.test.Test
import kotlin.test.assertFailsWith
import java.util.Base64

class ConfigSecurityTest {
	private fun environment() = mapOf(
		"DATABASE_URL" to "jdbc:postgresql://localhost/kotatsuredo",
		"DATABASE_USER" to "kotatsuredo",
		"DATABASE_PASSWORD" to "a-real-database-password",
		"DEVICE_PEPPER" to "0123456789abcdef0123456789abcdef",
		"MOD_TOTP_ENCRYPTION_KEY" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
	)

	@Test
	fun `the documented placeholder device pepper is rejected`() {
		assertFailsWith<IllegalArgumentException> {
			Config.fromEnv(environment() + ("DEVICE_PEPPER" to "generate-a-long-random-string"))
		}
	}

	@Test
	fun `invalid pool sizes are rejected`() {
		assertFailsWith<IllegalArgumentException> {
			Config.fromEnv(environment() + ("DATABASE_POOL_SIZE" to "0"))
		}
	}

	@Test
	fun `malformed numeric settings are rejected instead of silently defaulting`() {
		listOf("PORT", "DATABASE_POOL_SIZE", "COMMENT_MIN_LENGTH").forEach { key ->
			assertFailsWith<IllegalArgumentException>(key) {
				Config.fromEnv(environment() + (key to "not-a-number"))
			}
		}
	}

	@Test
	fun `bootstrap credentials must be configured as a pair`() {
		assertFailsWith<IllegalArgumentException> {
			Config.fromEnv(environment() + ("MOD_BOOTSTRAP_USERNAME" to "root"))
		}
	}

	@Test
	fun `production rules link must use https`() {
		assertFailsWith<IllegalArgumentException> {
			Config.fromEnv(environment() + mapOf("APP_ENV" to "production", "RULES_URL" to "/rules"))
		}
	}

	@Test
	fun `production cannot disable secure cookies`() {
		assertFailsWith<IllegalArgumentException> {
			Config.fromEnv(
				environment() + mapOf(
					"APP_ENV" to "production",
					"RULES_URL" to "https://example.test/rules",
					"SECURE_COOKIES" to "false",
				),
			)
		}
	}

	@Test
	fun `production rejects the documented database password placeholders`() {
		val production = environment() + mapOf(
			"APP_ENV" to "production",
			"RULES_URL" to "https://example.test/rules",
		)
		assertFailsWith<IllegalArgumentException> {
			Config.fromEnv(production + ("DATABASE_PASSWORD" to "generate-a-separate-runtime-password"))
		}
		assertFailsWith<IllegalArgumentException> {
			Config.migrationDatabaseFromEnv(
				environment() + mapOf(
					"DATABASE_MIGRATION_USER" to "owner",
					"DATABASE_MIGRATION_PASSWORD" to "generate-a-migration-owner-password",
				),
			)
		}
	}

	@Test
	fun `migration credentials are mandatory and separate from runtime credentials`() {
		assertFailsWith<IllegalStateException> { Config.migrationDatabaseFromEnv(environment()) }

		val migration = Config.migrationDatabaseFromEnv(
			environment() + mapOf(
				"DATABASE_MIGRATION_USER" to "owner",
				"DATABASE_MIGRATION_PASSWORD" to "a-distinct-owner-password",
			),
		)
		kotlin.test.assertEquals("owner", migration.user)
	}
}
