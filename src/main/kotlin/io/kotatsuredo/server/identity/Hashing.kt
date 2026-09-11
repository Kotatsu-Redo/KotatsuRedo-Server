package io.kotatsuredo.server.identity

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

fun sha256(vararg parts: ByteArray): ByteArray {
	val digest = MessageDigest.getInstance("SHA-256")
	parts.forEach(digest::update)
	return digest.digest()
}

/**
 * The user id shown everywhere: `base64url(sha256(secret))` truncated to 22 characters, which is
 * 132 bits - opaque, stable, and carrying nothing about the person (PLAN.md §1).
 */
fun userIdFrom(secretHash: ByteArray): String = urlEncoder.encodeToString(secretHash).take(USER_ID_LENGTH)

const val USER_ID_LENGTH = 22

/**
 * Device identifiers are peppered before hashing so that the stored value is useless outside this
 * server: without the pepper, an attacker holding the database cannot test a guessed ANDROID_ID
 * against it. The pepper never leaves the server and is not derived from anything in the database.
 */
class DevicePepper(private val pepper: ByteArray) {

	fun hash(value: String): ByteArray = sha256(pepper, value.toByteArray(Charsets.UTF_8))

	companion object {
		fun of(secret: String) = DevicePepper(secret.toByteArray(Charsets.UTF_8))
	}
}

/** Constant-time comparison, so a stored hash cannot be recovered by timing a sequence of guesses. */
fun ByteArray.constantTimeEquals(other: ByteArray): Boolean = MessageDigest.isEqual(this, other)

/**
 * The daily telemetry pseudonym: `HMAC(user secret, "YYYY-MM-DD")`.
 *
 * Keyed by the **secret**, deliberately, rather than by a server pepper over the user id. The server
 * never stores secrets, so once the request is over nobody - including whoever holds the database and
 * every server-side key - can recompute which user a given pseudonym belonged to. A pepper-over-user_id
 * construction would look similar and be retroactively reversible for every user at once.
 *
 * Rotating daily costs nothing, because popularity only ever needs distinct reporters *per day*
 * (PLAN.md §4, §6).
 */
fun dailyReporterId(secret: String, day: String): String {
	val mac = Mac.getInstance("HmacSHA256")
	mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
	return urlEncoder.encodeToString(mac.doFinal(day.toByteArray(Charsets.UTF_8))).take(REPORTER_ID_LENGTH)
}

const val REPORTER_ID_LENGTH = 22
