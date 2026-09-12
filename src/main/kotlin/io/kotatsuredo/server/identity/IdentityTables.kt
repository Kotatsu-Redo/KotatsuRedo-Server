package io.kotatsuredo.server.identity

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object AppUsers : Table("app_user") {
	val id = text("id")
	val secretHash = binary("secret_sha256")
	val nickname = text("nickname").nullable()
	val createdAt = timestampWithTimeZone("created_at")
	val lastSeenAt = timestampWithTimeZone("last_seen_at")
	val isBanned = bool("is_banned")
	val banReason = text("ban_reason").nullable()
	val isShadowbanned = bool("is_shadowbanned")

	override val primaryKey = PrimaryKey(id)
}

/**
 * Every secret that speaks for an account. An account starts with one and gains another when a phone
 * that lost its key is recognised by its hardware; both keep working, because the same key is allowed
 * to live on two phones and overwriting would lock the other one out.
 */
const val SECRET_ORIGIN_SIGNUP = "signup"
const val SECRET_ORIGIN_RESTORE = "device_restore"

object UserSecrets : Table("user_secret") {
	val secretHash = binary("secret_sha256")
	val userId = text("user_id").references(AppUsers.id)
	val origin = text("origin")
	val createdAt = timestampWithTimeZone("created_at")

	override val primaryKey = PrimaryKey(secretHash)
}

object UserActiveDays : Table("user_active_day") {
	val userId = text("user_id").references(AppUsers.id)
	val day = date("day")

	override val primaryKey = PrimaryKey(userId, day)
}

object AppDevices : Table("app_device") {
	val userId = text("user_id").references(AppUsers.id)
	val ssaidHash = binary("ssaid_hash")
	val drmHash = binary("drm_hash").nullable()
	val firstSeenAt = timestampWithTimeZone("first_seen_at")

	override val primaryKey = PrimaryKey(userId)
}

object DeviceBans : Table("device_ban") {
	val ssaidHash = binary("ssaid_hash")
	val drmHash = binary("drm_hash").nullable()
	val bannedAt = timestampWithTimeZone("banned_at")
	val byModerator = text("by_moderator").nullable()
	val reason = text("reason").nullable()

	override val primaryKey = PrimaryKey(ssaidHash)
}

object BanEvasionFlags : Table("ban_evasion_flag") {
	val id = long("id").autoIncrement()
	val userId = text("user_id").references(AppUsers.id)
	val matchedDrmHash = binary("matched_drm_hash")
	val createdAt = timestampWithTimeZone("created_at")
	val reviewedAt = timestampWithTimeZone("reviewed_at").nullable()

	override val primaryKey = PrimaryKey(id)
}
