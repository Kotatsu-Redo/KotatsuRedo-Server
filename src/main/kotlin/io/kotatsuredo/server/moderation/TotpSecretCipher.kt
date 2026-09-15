package io.kotatsuredo.server.moderation

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts database-resident TOTP seeds with an operator key kept outside PostgreSQL. */
class TotpSecretCipher private constructor(private val key: SecretKeySpec) {
	private val random = SecureRandom()

	fun encrypt(secret: String): String {
		val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
		val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
		return PREFIX + encoder.encodeToString(nonce + encrypted)
	}

	/** Plain values are accepted only to migrate databases written by older server versions. */
	fun decrypt(stored: String): String {
		if (!isEncrypted(stored)) return stored
		val payload = decoder.decode(stored.removePrefix(PREFIX))
		require(payload.size > NONCE_BYTES) { "invalid encrypted TOTP secret" }
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, NONCE_BYTES)))
		return cipher.doFinal(payload.copyOfRange(NONCE_BYTES, payload.size)).toString(Charsets.UTF_8)
	}

	fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

	companion object {
		private const val PREFIX = "enc:v1:"
		private const val TRANSFORMATION = "AES/GCM/NoPadding"
		private const val NONCE_BYTES = 12
		private const val TAG_BITS = 128
		private val encoder = Base64.getUrlEncoder().withoutPadding()
		private val decoder = Base64.getUrlDecoder()

		fun fromEncoded(encoded: String): TotpSecretCipher = fromRawKey(decoder.decode(encoded))

		fun fromRawKey(key: ByteArray): TotpSecretCipher {
			require(key.size == 32) { "MOD_TOTP_ENCRYPTION_KEY must decode to exactly 32 bytes" }
			return TotpSecretCipher(SecretKeySpec(key.copyOf(), "AES"))
		}
	}
}
