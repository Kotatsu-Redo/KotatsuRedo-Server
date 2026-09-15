package io.kotatsuredo.server.moderation

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing for moderator accounts.
 *
 * PBKDF2 from the JDK rather than bcrypt or Argon2, which would each be a new dependency for a table
 * that will hold single-digit numbers of rows. It is the weakest of the three against a GPU, and for
 * a handful of accounts behind TOTP that is an acceptable trade - the second factor, not the KDF, is
 * what is actually holding the door.
 *
 * The stored form carries its own iteration count, so raising [ITERATIONS] later does not invalidate
 * existing passwords: they keep verifying at the count they were written with.
 */
object Passwords {

	private const val ALGORITHM = "PBKDF2WithHmacSHA256"
	private const val PREFIX = "pbkdf2_sha256"
	private const val SEPARATOR = '$'
	private const val SALT_BYTES = 16
	private const val KEY_BITS = 256

	/** OWASP's 2023 floor for PBKDF2-HMAC-SHA256. */
	const val ITERATIONS = 600_000

	const val MIN_LENGTH = 12
	const val MAX_LENGTH = 1024

	private val random = SecureRandom()
	private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
	private val decoder: Base64.Decoder = Base64.getDecoder()
	private val dummyHash: String by lazy { hash("not-a-real-moderator-password") }

	fun hash(password: String, iterations: Int = ITERATIONS): String {
		val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
		val derived = derive(password, salt, iterations)
		return listOf(
			PREFIX,
			iterations.toString(),
			encoder.encodeToString(salt),
			encoder.encodeToString(derived),
		).joinToString(SEPARATOR.toString())
	}

	/**
	 * Compares in constant time. A timing oracle on a login endpoint is a real way to learn a hash
	 * prefix, and [MessageDigest.isEqual] is the JDK's constant-time comparison.
	 */
	fun verify(password: String, stored: String): Boolean {
		val parts = stored.split(SEPARATOR)
		if (parts.size != 4 || parts[0] != PREFIX) return false
		val iterations = parts[1].toIntOrNull() ?: return false
		val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
		val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
		return MessageDigest.isEqual(derive(password, salt, iterations), expected)
	}

	/** Performs the same KDF work for an unknown account as for a real one. */
	fun verifyOrDummy(password: String, stored: String?): Boolean =
		verify(password, stored ?: dummyHash) && stored != null

	private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
		val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
		return try {
			SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
		} finally {
			spec.clearPassword()
		}
	}
}
