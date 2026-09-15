package io.kotatsuredo.server

import io.kotatsuredo.server.moderation.Base32
import io.kotatsuredo.server.moderation.Passwords
import io.kotatsuredo.server.moderation.Totp
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two hand-rolled security primitives.
 *
 * Both are small enough to write and small enough to get subtly wrong, which is exactly the pair of
 * properties that makes tests non-optional: a TOTP implementation that is off by one bit still looks
 * like it works until the day nobody can log in.
 */
class ModeratorCredentialsTest {

	// A low iteration count keeps the suite fast. The production value is asserted separately.
	private fun hash(password: String) = Passwords.hash(password, iterations = 1_000)

	@Test
	fun `a password verifies against its own hash and nothing else`() {
		val stored = hash("correct horse battery staple")
		assertTrue(Passwords.verify("correct horse battery staple", stored))
		assertFalse(Passwords.verify("Correct horse battery staple", stored))
		assertFalse(Passwords.verify("", stored))
	}

	@Test
	fun `the same password hashes differently every time`() {
		// If two hashes of one password matched, the salt would not be doing anything, and a stolen
		// table would tell an attacker which moderators share a password.
		assertFalse(hash("same password") == hash("same password"))
	}

	@Test
	fun `a hash carries the iteration count it was made with`() {
		val stored = hash("a password worth keeping")
		assertContains(stored, "1000")
		// Which is what lets the cost be raised later without invalidating anyone's password.
		assertTrue(Passwords.verify("a password worth keeping", stored))
	}

	@Test
	fun `a malformed stored hash is rejected rather than throwing`() {
		listOf("", "not-a-hash", "pbkdf2_sha256\$abc\$x\$y", "bcrypt\$1000\$aa\$bb").forEach { stored ->
			assertFalse(Passwords.verify("anything", stored), "accepted: $stored")
		}
	}

	@Test
	fun `production iterations stay at the recommended floor`() {
		assertTrue(Passwords.ITERATIONS >= 600_000, "PBKDF2 iterations were lowered to ${Passwords.ITERATIONS}")
	}

	// -- TOTP ------------------------------------------------------------------------------------

	@Test
	fun `base32 round-trips`() {
		val bytes = ByteArray(20) { (it * 7 + 3).toByte() }
		assertTrue(Base32.decode(Base32.encode(bytes)).contentEquals(bytes))
	}

	@Test
	fun `base32 ignores spacing and case, because people type these by hand`() {
		val secret = Totp.generateSecret()
		val spaced = secret.lowercase().chunked(4).joinToString(" ")
		assertTrue(Base32.decode(spaced).contentEquals(Base32.decode(secret)))
	}

	/**
	 * RFC 6238's published test vector, with the SHA-1 key `12345678901234567890`.
	 *
	 * A hand-rolled TOTP that has never been checked against the spec's own numbers is a guess. This
	 * is the difference between "it agrees with itself" and "it agrees with Google Authenticator".
	 */
	@Test
	fun `codes match the RFC 6238 test vector`() {
		val secret = Base32.encode("12345678901234567890".toByteArray(Charsets.US_ASCII))
		assertEquals("287082", Totp.code(secret, Totp.timeStep(59L)))
		assertEquals("081804", Totp.code(secret, Totp.timeStep(1_111_111_109L)))
		assertEquals("050471", Totp.code(secret, Totp.timeStep(1_111_111_111L)))
		assertEquals("005924", Totp.code(secret, Totp.timeStep(1_234_567_890L)))
		assertEquals("279037", Totp.code(secret, Totp.timeStep(2_000_000_000L)))
	}

	@Test
	fun `a code is accepted one step either side and refused beyond`() {
		val secret = Totp.generateSecret()
		val now = 1_700_000_000L
		val code = Totp.code(secret, Totp.timeStep(now))

		assertNotNull(Totp.verify(secret, code, now))
		assertNotNull(Totp.verify(secret, code, now + Totp.STEP_SECONDS))
		assertNotNull(Totp.verify(secret, code, now - Totp.STEP_SECONDS))
		// Two steps out is a phone whose clock is a minute wrong, which is a support problem rather
		// than a reason to widen the window a replayed code lives in.
		assertNull(Totp.verify(secret, code, now + 3 * Totp.STEP_SECONDS))
	}

	@Test
	fun `spacing in a submitted code does not matter`() {
		val secret = Totp.generateSecret()
		val now = 1_700_000_000L
		val code = Totp.code(secret, Totp.timeStep(now))
		assertNotNull(Totp.verify(secret, "${code.take(3)} ${code.drop(3)}", now))
	}

	@Test
	fun `the provisioning uri names the issuer and carries the secret`() {
		val secret = Totp.generateSecret()
		val uri = Totp.provisioningUri(secret, "alice", "Kotatsu-Redo")
		assertTrue(uri.startsWith("otpauth://totp/"))
		assertContains(uri, "secret=$secret")
		assertContains(uri, "issuer=Kotatsu-Redo")
	}

	@Test
	fun `totp seeds are authenticated and randomized at rest`() {
		val cipher = testTotpCipher()
		val first = cipher.encrypt("JBSWY3DPEHPK3PXP")
		val second = cipher.encrypt("JBSWY3DPEHPK3PXP")
		assertNotEquals(first, second)
		assertFalse(first.contains("JBSWY3DPEHPK3PXP"))
		assertEquals("JBSWY3DPEHPK3PXP", cipher.decrypt(first))
	}
}

internal fun testTotpCipher(): io.kotatsuredo.server.moderation.TotpSecretCipher =
	io.kotatsuredo.server.moderation.TotpSecretCipher.fromRawKey(ByteArray(32) { 0x42 })
