package io.kotatsuredo.server.identity

import java.time.OffsetDateTime

/** A user is a key. Nothing here identifies a person. */
data class Identity(
	val id: String,
	val nickname: String?,
	val createdAt: OffsetDateTime,
	val isBanned: Boolean,
	val banReason: String?,
	val isShadowbanned: Boolean,
) {
	val displayName: String get() = Nicknames.display(nickname, id)
}

enum class TrustTier(val level: Int) {
	/** Under 24h old, or fewer than 3 active days. Tight rate limits; publication is never gated. */
	NEW(0),
	NORMAL(1),
	/** At least 90 days old, active on 30 days, and never sanctioned. */
	ESTABLISHED(2),
	;

	companion object {
		fun of(level: Int): TrustTier = entries.firstOrNull { it.level == level } ?: NORMAL
	}
}

/**
 * Device values are sent **only** at identity creation, never on ordinary requests (PLAN.md §1).
 * [drmId] is absent on devices without a Widevine plugin, which includes many degoogled ROMs.
 */
data class DeviceIdentifiers(
	val ssaid: String,
	val drmId: String?,
)

sealed interface HelloOutcome {
	/**
	 * @property created a new account was made for this key.
	 * @property restored retained for wire compatibility with older clients; secure deployments
	 *  always return false because device identifiers are not credentials.
	 */
	data class Ok(
		val identity: Identity,
		val tier: TrustTier,
		val created: Boolean,
		val restored: Boolean = false,
	) : HelloOutcome

	/** The device is banned. No account is created; the caller gets `banned` and nothing else. */
	data object DeviceBanned : HelloOutcome
}

/**
 * A device ban as the panel sees it. The hash is peppered and one-way, so it identifies a device to
 * this server and to nothing else - which is exactly what makes it safe to show a moderator.
 */
data class BannedDevice(
	val ssaidHash: ByteArray,
	val bannedAt: OffsetDateTime,
	val byModerator: String?,
	val reason: String?,
) {
	/** Short, stable, and the handle the panel passes back to reverse the ban. */
	val fingerprint: String get() = ssaidHash.joinToString("") { "%02x".format(it) }.take(16)

	override fun equals(other: Any?) = other is BannedDevice && ssaidHash.contentEquals(other.ssaidHash)

	override fun hashCode() = ssaidHash.contentHashCode()
}
