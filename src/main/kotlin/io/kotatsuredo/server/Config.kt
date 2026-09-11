package io.kotatsuredo.server

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
) {

	val isProduction: Boolean get() = environment == "production"

	companion object {

		fun fromEnv(env: Map<String, String> = System.getenv()): Config = Config(
			port = env.optional("PORT")?.toIntOrNull() ?: 8080,
			host = env.optional("HOST") ?: "0.0.0.0",
			environment = env.optional("APP_ENV") ?: "development",
			devicePepper = env.required("DEVICE_PEPPER"),
			rulesUrl = env.optional("RULES_URL") ?: "/rules",
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
