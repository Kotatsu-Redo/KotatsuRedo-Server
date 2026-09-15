package io.kotatsuredo.server

import io.kotatsuredo.server.comments.CommentRules
import java.util.Base64

/**
 * All configuration comes from the environment. Nothing is read from a committed file, so a
 * misconfigured deployment fails loudly at startup rather than silently running on a default.
 *
 * See `.env.example` for the full set.
 */
data class Config(
	val port: Int,
	val host: String,
	val database: DatabaseConfig,
	val environment: String,
	/** Trust X-Forwarded-For only when traffic is forced through the deployment's reverse proxy. */
	val trustProxyHeaders: Boolean,
	/** Independent of APP_ENV so TLS-terminated development/staging deployments cannot emit unsafe cookies. */
	val secureCookies: Boolean,
	/**
	 * Peppers the device hashes so a stolen database cannot be tested against a guessed ANDROID_ID.
	 * Required, with no default: a hardcoded fallback would silently make every deployment's hashes
	 * interchangeable. **Changing it invalidates every existing device ban.**
	 */
	val devicePepper: String,
	/** AES-256 key used to keep moderator TOTP seeds confidential in database-only backups/dumps. */
	val totpEncryptionKey: String,
	val rulesUrl: String,
	/**
	 * The first admin account, created once on a server whose `moderator` table is empty and ignored
	 * from then on. Leaving these set is harmless but pointless - the path disables itself (§6).
	 */
	val bootstrapModerator: Pair<String, String>?,
	/** Shortest comment the server will take. Tunable without a rebuild; see CommentService. */
	val commentMinLength: Int,
) {

	val isProduction: Boolean get() = environment == "production"

	private fun validate() {
		require(port in 1..65_535) { "PORT must be between 1 and 65535" }
		require(environment in setOf("development", "test", "production")) {
			"APP_ENV must be development, test, or production"
		}
		require(database.url.startsWith("jdbc:postgresql:")) { "DATABASE_URL must be a PostgreSQL JDBC URL" }
		require(database.maxPoolSize in 1..128) { "DATABASE_POOL_SIZE must be between 1 and 128" }
		require(commentMinLength in 1..CommentRules.MAX_BODY_LENGTH) {
			"COMMENT_MIN_LENGTH must be between 1 and ${CommentRules.MAX_BODY_LENGTH}"
		}
		require(devicePepper.length >= MIN_DEVICE_PEPPER_LENGTH && devicePepper != EXAMPLE_DEVICE_PEPPER) {
			"DEVICE_PEPPER must be a unique random secret of at least $MIN_DEVICE_PEPPER_LENGTH characters"
		}
		require(runCatching { Base64.getUrlDecoder().decode(totpEncryptionKey).size == 32 }.getOrDefault(false)) {
			"MOD_TOTP_ENCRYPTION_KEY must be base64url encoding of exactly 32 random bytes"
		}
		if (isProduction) {
			require(secureCookies) { "SECURE_COOKIES must be true in production" }
			require(rulesUrl.startsWith("https://")) { "RULES_URL must be an absolute HTTPS URL in production" }
			require(database.password !in EXAMPLE_DATABASE_PASSWORDS) {
				"DATABASE_PASSWORD must not use an example value in production"
			}
		}
	}

	companion object {

		/**
		 * Not 8080. Nothing else on the box should be holding this, and on a VPS 8080 usually is:
		 * it is the first port any other JVM, dev server or admin panel reaches for. The api is only
		 * reachable over the compose network in a real deployment, so this mostly matters for the
		 * local overlay that publishes it - but a default that collides is a default worth changing.
		 *
		 * Change `PORT` in `.env` to move it; `docker-compose.yml` and the Caddyfile both follow.
		 */
		const val DEFAULT_PORT = 8787

		fun fromEnv(env: Map<String, String> = System.getenv()): Config {
			val bootstrapUsername = env.optional("MOD_BOOTSTRAP_USERNAME")
			val bootstrapPassword = env.optional("MOD_BOOTSTRAP_PASSWORD")
			val environment = env.optional("APP_ENV") ?: "development"
			val databaseUser = env.required("DATABASE_USER")
			val databasePassword = env.required("DATABASE_PASSWORD")
			require((bootstrapUsername == null) == (bootstrapPassword == null)) {
				"MOD_BOOTSTRAP_USERNAME and MOD_BOOTSTRAP_PASSWORD must be set together"
			}
			val database = DatabaseConfig(
				url = env.required("DATABASE_URL"),
				user = databaseUser,
				password = databasePassword,
				maxPoolSize = env.optionalInt("DATABASE_POOL_SIZE") ?: 10,
			)
			return Config(
				port = env.optionalInt("PORT") ?: DEFAULT_PORT,
				host = env.optional("HOST") ?: if (environment == "production") "0.0.0.0" else "127.0.0.1",
				environment = environment,
				trustProxyHeaders = env.optionalBoolean("TRUST_PROXY_HEADERS") ?: false,
				secureCookies = env.optionalBoolean("SECURE_COOKIES") ?: (environment == "production"),
				devicePepper = env.required("DEVICE_PEPPER"),
				totpEncryptionKey = env.required("MOD_TOTP_ENCRYPTION_KEY"),
				rulesUrl = env.optional("RULES_URL") ?: "/rules",
				commentMinLength = env.optionalInt("COMMENT_MIN_LENGTH")
					?: CommentRules.MIN_BODY_LENGTH,
				bootstrapModerator = bootstrapUsername?.let { it to requireNotNull(bootstrapPassword) },
				database = database,
			).also { it.validate() }
		}

		/**
		 * Configuration for the one-shot migration command. Keeping this separate from [fromEnv]
		 * means the long-lived API process never needs to receive the schema-owner password.
		 */
		fun migrationDatabaseFromEnv(env: Map<String, String> = System.getenv()): DatabaseConfig {
			val url = env.required("DATABASE_URL")
			val password = env.required("DATABASE_MIGRATION_PASSWORD")
			require(url.startsWith("jdbc:postgresql:")) {
				"DATABASE_URL must be a PostgreSQL JDBC URL"
			}
			require(password !in EXAMPLE_DATABASE_PASSWORDS) {
				"DATABASE_MIGRATION_PASSWORD must not use an example value"
			}
			return DatabaseConfig(
				url = url,
				user = env.required("DATABASE_MIGRATION_USER"),
				password = password,
				maxPoolSize = 2,
			)
		}

		private const val MIN_DEVICE_PEPPER_LENGTH = 32
		private const val EXAMPLE_DEVICE_PEPPER = "generate-a-long-random-string"
		private val EXAMPLE_DATABASE_PASSWORDS = setOf(
			"change-me",
			"generate-a-separate-runtime-password",
			"generate-a-migration-owner-password",
		)

		private fun Map<String, String>.optional(key: String): String? =
			get(key)?.trim()?.takeIf { it.isNotEmpty() }

		private fun Map<String, String>.optionalInt(key: String): Int? {
			val value = optional(key) ?: return null
			return value.toIntOrNull()
				?: throw IllegalArgumentException("$key must be an integer")
		}

		private fun Map<String, String>.optionalBoolean(key: String): Boolean? = when (val value = optional(key)) {
			null -> null
			"true" -> true
			"false" -> false
			else -> throw IllegalArgumentException("$key must be true or false")
		}

		private fun Map<String, String>.required(key: String): String = optional(key)
			?: throw IllegalStateException(
				"Missing required environment variable $key. See .env.example.",
			)
	}
}

data class DatabaseConfig(
	val url: String,
	val user: String,
	val password: String,
	val maxPoolSize: Int,
)
