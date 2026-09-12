package io.kotatsuredo.server

import io.kotatsuredo.server.comments.CommentRules

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
	/**
	 * Peppers the device hashes so a stolen database cannot be tested against a guessed ANDROID_ID.
	 * Required, with no default: a hardcoded fallback would silently make every deployment's hashes
	 * interchangeable. **Changing it invalidates every existing device ban.**
	 */
	val devicePepper: String,
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

		fun fromEnv(env: Map<String, String> = System.getenv()): Config = Config(
			port = env.optional("PORT")?.toIntOrNull() ?: DEFAULT_PORT,
			host = env.optional("HOST") ?: "0.0.0.0",
			environment = env.optional("APP_ENV") ?: "development",
			devicePepper = env.required("DEVICE_PEPPER"),
			rulesUrl = env.optional("RULES_URL") ?: "/rules",
			commentMinLength = env.optional("COMMENT_MIN_LENGTH")?.toIntOrNull()
				?: CommentRules.MIN_BODY_LENGTH,
			bootstrapModerator = env.optional("MOD_BOOTSTRAP_USERNAME")
				?.let { username -> env.optional("MOD_BOOTSTRAP_PASSWORD")?.let { username to it } },
			database = DatabaseConfig(
				url = env.required("DATABASE_URL"),
				user = env.required("DATABASE_USER"),
				password = env.required("DATABASE_PASSWORD"),
				maxPoolSize = env.optional("DATABASE_POOL_SIZE")?.toIntOrNull() ?: 10,
			),
		)

		private fun Map<String, String>.optional(key: String): String? =
			get(key)?.trim()?.takeIf { it.isNotEmpty() }

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
