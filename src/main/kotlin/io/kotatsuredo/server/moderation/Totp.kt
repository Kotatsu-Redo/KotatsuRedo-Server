package io.kotatsuredo.server.moderation

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP, hand-rolled because it is forty lines and every library that does it drags in a
 * QR encoder, a servlet shim, or both.
 *
 * HMAC-SHA1 with a 30-second step and six digits: not a choice so much as what Google Authenticator,
 * Aegis, 1Password and the rest actually implement. A server that picks SHA-256 here is a server
 * whose moderators cannot enrol.
 */
object Totp {

	const val DIGITS = 6
	const val STEP_SECONDS = 30L

	/**
	 * How many steps either side of now are accepted. One step is ±30 seconds of clock skew, which
	 * covers a phone that has never synced; more than that widens the replay window for no real gain.
	 */
	const val SKEW_STEPS = 1

	private const val ALGORITHM = "HmacSHA1"
	private const val SECRET_BYTES = 20

	private val random = SecureRandom()

	fun generateSecret(): String = Base32.encode(ByteArray(SECRET_BYTES).also(random::nextBytes))

	fun timeStep(epochSecond: Long): Long = epochSecond / STEP_SECONDS

	fun code(secret: String, timeStep: Long): String {
		val key = SecretKeySpec(Base32.decode(secret), ALGORITHM)
		val message = ByteArray(8)
		var value = timeStep
		for (index in 7 downTo 0) {
			message[index] = (value and 0xff).toByte()
			value = value shr 8
		}

		val digest = Mac.getInstance(ALGORITHM).run {
			init(key)
			doFinal(message)
		}
		// Dynamic truncation, RFC 4226 §5.3: the low nibble of the last byte picks the offset.
		val offset = (digest[digest.size - 1].toInt() and 0x0f)
		val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
			((digest[offset + 1].toInt() and 0xff) shl 16) or
			((digest[offset + 2].toInt() and 0xff) shl 8) or
			(digest[offset + 3].toInt() and 0xff)

		var modulus = 1
		repeat(DIGITS) { modulus *= 10 }
		return (binary % modulus).toString().padStart(DIGITS, '0')
	}

	/**
	 * @return the time step the code belongs to, or null if it matches none. The caller burns that
	 *  step so the same code cannot be presented twice - a code stays valid for thirty seconds, which
	 *  is long enough for one to be read over a shoulder and used.
	 */
	fun verify(secret: String, submitted: String, epochSecond: Long): Long? {
		val cleaned = submitted.filter { it.isDigit() }
		if (cleaned.length != DIGITS) return null
		val current = timeStep(epochSecond)
		for (offset in -SKEW_STEPS..SKEW_STEPS) {
			val step = current + offset
			if (constantTimeEquals(code(secret, step), cleaned)) return step
		}
		return null
	}

	/** The `otpauth://` URI an authenticator app scans. Never logged: it contains the secret. */
	fun provisioningUri(secret: String, account: String, issuer: String): String {
		val label = URLEncoder.encode("$issuer:$account", Charsets.UTF_8)
		val encodedIssuer = URLEncoder.encode(issuer, Charsets.UTF_8)
		return "otpauth://totp/$label?secret=$secret&issuer=$encodedIssuer&digits=$DIGITS&period=$STEP_SECONDS"
	}

	private fun constantTimeEquals(a: String, b: String) =
		MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
}

/**
 * Base32 as authenticator apps expect it: RFC 4648 alphabet, uppercase, padding optional on the way
 * in and omitted on the way out (every app copes, and it keeps the string a moderator may have to
 * type by hand as short as possible).
 */
object Base32 {

	private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

	fun encode(bytes: ByteArray): String {
		val builder = StringBuilder()
		var buffer = 0
		var bitsLeft = 0
		for (byte in bytes) {
			buffer = (buffer shl 8) or (byte.toInt() and 0xff)
			bitsLeft += 8
			while (bitsLeft >= 5) {
				builder.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1f])
				bitsLeft -= 5
			}
		}
		if (bitsLeft > 0) builder.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1f])
		return builder.toString()
	}

	fun decode(encoded: String): ByteArray {
		val cleaned = encoded.uppercase().filter { it in ALPHABET }
		val out = ByteArray(cleaned.length * 5 / 8)
		var buffer = 0
		var bitsLeft = 0
		var index = 0
		for (character in cleaned) {
			buffer = (buffer shl 5) or ALPHABET.indexOf(character)
			bitsLeft += 5
			if (bitsLeft >= 8) {
				out[index++] = ((buffer shr (bitsLeft - 8)) and 0xff).toByte()
				bitsLeft -= 8
			}
		}
		return out
	}
}
