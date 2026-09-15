package io.kotatsuredo.server.routes

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.enforceLimit
import io.kotatsuredo.server.auth.opaqueRateLimitKey
import io.kotatsuredo.server.identity.BannedDevice
import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.moderation.EnrolResult
import io.kotatsuredo.server.moderation.LoginResult
import io.kotatsuredo.server.moderation.ModActionRecord
import io.kotatsuredo.server.moderation.ModSession
import io.kotatsuredo.server.moderation.ModerationRules
import io.kotatsuredo.server.moderation.ModerationQueueRepository
import io.kotatsuredo.server.moderation.ModerationService
import io.kotatsuredo.server.moderation.OverviewRepository
import io.kotatsuredo.server.moderation.PasswordChangeResult
import io.kotatsuredo.server.moderation.Moderator
import io.kotatsuredo.server.moderation.ModeratorRole
import io.kotatsuredo.server.moderation.QueuedBanEvasion
import io.kotatsuredo.server.moderation.QueuedBrigade
import io.kotatsuredo.server.moderation.QueuedComment
import io.kotatsuredo.server.moderation.QueuedDispute
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val SessionKey = AttributeKey<ModSession>("kotatsuredo.mod-session")

/** The panel is same-origin, so the session rides in a cookie the page's own JavaScript cannot read. */
const val MOD_SESSION_COOKIE = "kr_mod"
private const val MAX_LOGIN_USERNAME = 64
private const val MAX_LOGIN_PASSWORD = 1024
private const val MAX_LOGIN_CODE = 16
internal const val ADMIN_CSRF_HEADER = "X-KotatsuRedo-CSRF"
internal const val ADMIN_CSRF_VALUE = "1"
private val passwordVerificationDispatcher = Dispatchers.Default.limitedParallelism(2)

private fun OffsetDateTime.iso(): String = DateTimeFormatter.ISO_INSTANT.format(toInstant())

// -- DTOs ----------------------------------------------------------------------------------------

@Serializable
data class LoginRequest(val username: String, val password: String, val code: String? = null)

@Serializable
data class ModeratorDto(
	val id: String,
	val username: String,
	val role: String,
	val disabled: Boolean,
	@SerialName("totp_confirmed") val totpConfirmed: Boolean,
	@SerialName("created_at") val createdAt: String,
	@SerialName("last_login_at") val lastLoginAt: String? = null,
)

@Serializable
data class SessionDto(
	val moderator: ModeratorDto,
	@SerialName("expires_at") val expiresAt: String,
	/** True until the account enrols. While it is true, nothing else in the panel will answer. */
	@SerialName("needs_totp_enrolment") val needsTotpEnrolment: Boolean,
)

@Serializable
data class EnrolDto(val secret: String, val uri: String)

@Serializable
data class CodeRequest(val code: String)

@Serializable
data class ChangePasswordRequest(
	@SerialName("current_password") val currentPassword: String,
	@SerialName("new_password") val newPassword: String,
	val code: String,
)

@Serializable
data class InviteRequest(val username: String, val password: String, val role: String = "moderator")

@Serializable
data class UpdateModeratorRequest(val role: String? = null, val disabled: Boolean? = null)

@Serializable
data class ReasonRequest(val reason: String? = null)

@Serializable
data class MergeRequest(val from: String, val into: String, val reason: String)

@Serializable
data class QueuedCommentDto(
	val id: String,
	@SerialName("work_id") val workId: String,
	@SerialName("work_title") val workTitle: String,
	@SerialName("chapter_id") val chapterId: String? = null,
	val body: String,
	val lang: String? = null,
	/** visible | shadowed | removed - the panel is the one place all three are shown. */
	val state: String,
	val up: Int,
	val down: Int,
	val score: Double,
	@SerialName("created_at") val createdAt: String,
	@SerialName("author_id") val authorId: String,
	val author: String,
	@SerialName("author_banned") val authorBanned: Boolean,
	@SerialName("author_shadowbanned") val authorShadowbanned: Boolean,
	@SerialName("author_removed_count") val authorRemovedCount: Int,
	@SerialName("flagged_rule") val flaggedRule: String? = null,
)

@Serializable
data class CommentQueueResponse(val comments: List<QueuedCommentDto>)

@Serializable
data class BanEvasionDto(
	val id: String,
	@SerialName("user_id") val userId: String,
	val user: String,
	@SerialName("created_at") val createdAt: String,
	val comments: Int,
)

@Serializable
data class BrigadeDto(
	val id: String,
	@SerialName("work_id") val workId: String,
	@SerialName("work_title") val workTitle: String,
	@SerialName("detected_at") val detectedAt: String,
	@SerialName("recent_count") val recentCount: Int,
	val baseline: Double,
	@SerialName("extreme_share") val extremeShare: Double,
	@SerialName("new_user_share") val newUserShare: Double,
)

@Serializable
data class DisputeDto(
	val id: String,
	@SerialName("work_id") val workId: String,
	@SerialName("work_title") val workTitle: String,
	@SerialName("other_work_id") val otherWorkId: String? = null,
	@SerialName("other_work_title") val otherWorkTitle: String? = null,
	val kind: String,
	val note: String? = null,
	@SerialName("created_at") val createdAt: String,
)

@Serializable
data class DeviceBanDto(
	val fingerprint: String,
	@SerialName("banned_at") val bannedAt: String,
	@SerialName("by_moderator") val byModerator: String? = null,
	val reason: String? = null,
)

@Serializable
data class ActionDto(
	val id: String,
	val moderator: String,
	val action: String,
	@SerialName("target_type") val targetType: String,
	@SerialName("target_id") val targetId: String,
	val reason: String? = null,
	val detail: String? = null,
	@SerialName("created_at") val createdAt: String,
)

@Serializable
data class QueueCountsResponse(val counts: Map<String, Int>)

@Serializable
data class OverviewResponse(
	val totals: TotalsDto,
	val today: TodayDto,
	val queues: Map<String, Int>,
	val series: List<DayDto>,
	@SerialName("top_rules") val topRules: List<RuleLoadDto>,
	val languages: List<LanguageLoadDto>,
)

@Serializable
data class TotalsDto(
	val users: Int,
	val comments: Int,
	val removed: Int,
	val ratings: Int,
	val works: Int,
	val moderators: Int,
)

@Serializable
data class TodayDto(
	val comments: Int,
	val users: Int,
	val ratings: Int,
	val blocked: Int,
	val actions: Int,
)

@Serializable
data class DayDto(val day: String, val comments: Int, val users: Int)

@Serializable
data class RuleLoadDto(val term: String, val tier: String, val blocks: Int, val disputed: Int)

@Serializable
data class LanguageLoadDto(val lang: String, val comments: Int, val blocked: Int)

@Serializable
data class OkResponse(val ok: Boolean = true)

private fun Moderator.toDto() = ModeratorDto(
	id = id,
	username = username,
	role = role.name.lowercase(),
	disabled = isDisabled,
	totpConfirmed = totpConfirmed,
	createdAt = createdAt.iso(),
	lastLoginAt = lastLoginAt?.iso(),
)

private fun ModSession.toDto() = SessionDto(
	moderator = moderator.toDto(),
	expiresAt = expiresAt.iso(),
	needsTotpEnrolment = needsTotpEnrolment,
)

private fun QueuedComment.toDto() = QueuedCommentDto(
	id = id.toString(),
	workId = workId.toString(),
	workTitle = workTitle,
	chapterId = chapterId?.toString(),
	body = body,
	lang = lang,
	state = when (state.toInt()) {
		1 -> "shadowed"
		2 -> "removed"
		else -> "visible"
	},
	up = up,
	down = down,
	score = score,
	createdAt = createdAt.iso(),
	authorId = authorId,
	author = authorName,
	authorBanned = authorIsBanned,
	authorShadowbanned = authorIsShadowbanned,
	authorRemovedCount = authorRemovedCount,
	flaggedRule = flaggedRule,
)

private fun QueuedBanEvasion.toDto() = BanEvasionDto(
	id = id.toString(),
	userId = userId,
	user = userName,
	createdAt = createdAt.iso(),
	comments = userCommentCount,
)

private fun QueuedBrigade.toDto() = BrigadeDto(
	id = id.toString(),
	workId = workId.toString(),
	workTitle = workTitle,
	detectedAt = detectedAt.iso(),
	recentCount = recentCount,
	baseline = baseline,
	extremeShare = extremeShare,
	newUserShare = newUserShare,
)

private fun QueuedDispute.toDto() = DisputeDto(
	id = id.toString(),
	workId = workId.toString(),
	workTitle = workTitle,
	otherWorkId = otherWorkId?.toString(),
	otherWorkTitle = otherWorkTitle,
	kind = kind,
	note = note,
	createdAt = createdAt.iso(),
)

private fun BannedDevice.toDto() = DeviceBanDto(
	fingerprint = fingerprint,
	bannedAt = bannedAt.iso(),
	byModerator = byModerator,
	reason = reason,
)

private fun ModActionRecord.toDto() = ActionDto(
	id = id.toString(),
	moderator = moderatorName,
	action = action,
	targetType = targetType,
	targetId = targetId,
	reason = reason,
	detail = detail,
	createdAt = createdAt.iso(),
)

// -- routes --------------------------------------------------------------------------------------

/**
 * The moderation API.
 *
 * Mounted outside `/v1` on purpose: this is not the app's API, it has a different auth model, and
 * nothing an app client holds should ever be able to reach it.
 *
 * Auth is a session cookie rather than a bearer token, because the panel is a browser page: an
 * httpOnly cookie is unreadable to any script that manages to run on it, which a token in
 * `localStorage` is not. `SameSite=Strict` is what stops another site posting to these endpoints on
 * a logged-in moderator's behalf.
 */
fun Route.adminRoutes(
	moderation: ModerationService,
	queues: ModerationQueueRepository,
	secureCookies: Boolean,
	/** Null only in tests that do not exercise the home page. */
	overviews: OverviewRepository? = null,
	limiter: RateLimiter = RateLimiter(),
) =
	route("/admin/api") {

		post("/login") {
			val body = call.receive<LoginRequest>()
			if (
				body.username.isBlank() ||
				body.username.length > MAX_LOGIN_USERNAME ||
				body.password.length > MAX_LOGIN_PASSWORD ||
				body.code?.length?.let { it > MAX_LOGIN_CODE } == true
			) {
				call.respond(HttpStatusCode.Unauthorized, ErrorDto("invalid_credentials"))
				return@post
			}
			call.enforceLimit(
				limiter,
				RateLimiter.Bucket.ADMIN_LOGIN,
				TrustTier.NEW,
				opaqueRateLimitKey(body.username.trim().lowercase()),
			)
			val login = withContext(passwordVerificationDispatcher) {
				moderation.login(body.username, body.password, body.code)
			}
			when (val result = login) {
				is LoginResult.Ok -> {
					call.response.cookies.append(
						name = MOD_SESSION_COOKIE,
						value = result.token,
						httpOnly = true,
						secure = secureCookies,
						path = "/admin",
						maxAge = ModerationRules.SESSION_HOURS * 3600,
						extensions = mapOf("SameSite" to "Strict"),
						encoding = CookieEncoding.RAW,
					)
					call.respond(result.session.toDto())
				}

				LoginResult.TotpRequired -> call.respond(HttpStatusCode.Unauthorized, ErrorDto("totp_required"))
				LoginResult.Disabled -> call.respond(HttpStatusCode.Forbidden, ErrorDto("disabled"))
				LoginResult.InvalidCredentials ->
					call.respond(HttpStatusCode.Unauthorized, ErrorDto("invalid_credentials"))
			}
		}

		post("/logout") {
			call.request.cookies[MOD_SESSION_COOKIE]?.let(moderation::logout)
			call.response.cookies.append(
				name = MOD_SESSION_COOKIE,
				value = "",
				httpOnly = true,
				secure = secureCookies,
				path = "/admin",
				maxAge = 0,
				extensions = mapOf("SameSite" to "Strict"),
			)
			call.respond(OkResponse())
		}

		get("/me") {
			call.respond(call.requireSession(moderation).toDto())
		}

		// -- TOTP enrolment: the only thing an unenrolled account may do ---------------------------

		post("/totp/enroll") {
			val session = call.requireSession(moderation)
			val token = call.request.cookies[MOD_SESSION_COOKIE] ?: throw ApiException(ApiError.Unauthorized)
			when (val result = moderation.startEnrolment(session.moderator, token)) {
				is EnrolResult.Started -> call.respond(EnrolDto(result.secret, result.uri))
				EnrolResult.AlreadyEnrolled ->
					call.respond(HttpStatusCode.Conflict, ErrorDto("already_enrolled"))
				EnrolResult.InProgress ->
					call.respond(HttpStatusCode.Conflict, ErrorDto("enrolment_in_progress"))
			}
		}

		post("/totp/confirm") {
			val session = call.requireSession(moderation)
			val token = call.request.cookies[MOD_SESSION_COOKIE] ?: throw ApiException(ApiError.Unauthorized)
			if (!moderation.confirmEnrolment(session.moderator, token, call.receive<CodeRequest>().code)) {
				call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_code"))
				return@post
			}
			call.respond(OkResponse())
		}

		post("/password") {
			val session = call.requireEnrolled(moderation)
			call.enforceLimit(
				limiter,
				RateLimiter.Bucket.ADMIN_LOGIN,
				TrustTier.NEW,
				session.moderator.id,
			)
			val body = call.receive<ChangePasswordRequest>()
			if (body.currentPassword.length > MAX_LOGIN_PASSWORD ||
				body.newPassword.length > MAX_LOGIN_PASSWORD || body.code.length > MAX_LOGIN_CODE
			) {
				throw ApiException(ApiError.BadRequest("password"))
			}
			when (moderation.changePassword(
				session.moderator,
				body.currentPassword,
				body.newPassword,
				body.code,
			)) {
				PasswordChangeResult.CHANGED -> {
					call.response.cookies.append(
						name = MOD_SESSION_COOKIE,
						value = "",
						httpOnly = true,
						secure = secureCookies,
						path = "/admin",
						maxAge = 0,
						extensions = mapOf("SameSite" to "Strict"),
					)
					call.respond(OkResponse())
				}
				PasswordChangeResult.INVALID_CURRENT_PASSWORD ->
					call.respond(HttpStatusCode.Unauthorized, ErrorDto("invalid_current_password"))
				PasswordChangeResult.INVALID_TOTP ->
					call.respond(HttpStatusCode.Unauthorized, ErrorDto("bad_code"))
				PasswordChangeResult.INVALID_NEW_PASSWORD ->
					call.respond(HttpStatusCode.BadRequest, ErrorDto("weak_password"))
			}
		}

		// -- queues --------------------------------------------------------------------------------

		get("/overview") {
			call.requireEnrolled(moderation)
			val counts = queues.counts(DISLIKE_WINDOW_HOURS, MIN_DISLIKES)
			val overview = overviews?.load(counts)
				?: throw ApiException(ApiError.NotFound)
			call.respond(
				OverviewResponse(
					totals = with(overview.totals) {
						TotalsDto(users, comments, removed, ratings, works, moderators)
					},
					today = with(overview.today) { TodayDto(comments, users, ratings, blocked, actions) },
					queues = overview.queues,
					series = overview.series.map { DayDto(it.day, it.comments, it.users) },
					topRules = overview.topRules.map { RuleLoadDto(it.term, it.tier, it.blocks, it.disputed) },
					languages = overview.languages.map { LanguageLoadDto(it.lang, it.comments, it.blocked) },
				),
			)
		}

		get("/queues/counts") {
			call.requireEnrolled(moderation)
			call.respond(QueueCountsResponse(queues.counts(DISLIKE_WINDOW_HOURS, MIN_DISLIKES)))
		}

		get("/queues/disliked") {
			call.requireEnrolled(moderation)
			call.respond(
				CommentQueueResponse(
					queues.mostDisliked(DISLIKE_WINDOW_HOURS, MIN_DISLIKES, call.pageSize()).map { it.toDto() },
				),
			)
		}

		get("/queues/recent") {
			call.requireEnrolled(moderation)
			val before = call.request.queryParameters["before"]?.toLongOrNull()
			call.respond(CommentQueueResponse(queues.firehose(before, call.pageSize()).map { it.toDto() }))
		}

		get("/queues/flagged") {
			call.requireEnrolled(moderation)
			call.respond(CommentQueueResponse(queues.flagged(call.pageSize()).map { it.toDto() }))
		}

		get("/queues/ban-evasion") {
			call.requireEnrolled(moderation)
			call.respond(queues.banEvasionFlags(call.pageSize()).map { it.toDto() })
		}

		get("/queues/brigades") {
			call.requireEnrolled(moderation)
			call.respond(queues.brigadeFlags(call.pageSize()).map { it.toDto() })
		}

		get("/queues/disputes") {
			call.requireEnrolled(moderation)
			call.respond(queues.openDisputes(call.pageSize()).map { it.toDto() })
		}

		get("/users/{id}/comments") {
			call.requireEnrolled(moderation)
			val userId = call.parameters["id"] ?: throw ApiException(ApiError.BadRequest("id"))
			call.respond(CommentQueueResponse(queues.byAuthor(userId, call.pageSize()).map { it.toDto() }))
		}

		// -- comment actions -----------------------------------------------------------------------

		post("/comments/{id}/remove") {
			val session = call.requireEnrolled(moderation)
			val reason = call.receive<ReasonRequest>().requireReason()
			call.respondOk(moderation.removeComment(session.moderator, call.longId(), reason))
		}

		post("/comments/{id}/restore") {
			val session = call.requireEnrolled(moderation)
			val reason = call.receive<ReasonRequest>().reason
			call.respondOk(moderation.restoreComment(session.moderator, call.longId(), reason))
		}

		// -- user actions --------------------------------------------------------------------------

		post("/users/{id}/ban") {
			val session = call.requireEnrolled(moderation)
			val reason = call.receive<ReasonRequest>().requireReason()
			call.respondOk(moderation.banUser(session.moderator, call.stringId(), reason))
		}

		post("/users/{id}/unban") {
			val session = call.requireEnrolled(moderation)
			val reason = call.receive<ReasonRequest>().reason
			call.respondOk(moderation.unbanUser(session.moderator, call.stringId(), reason))
		}

		post("/users/{id}/shadowban") {
			val session = call.requireEnrolled(moderation)
			val reason = call.receive<ReasonRequest>().requireReason()
			call.respondOk(moderation.setShadowban(session.moderator, call.stringId(), true, reason))
		}

		post("/users/{id}/unshadowban") {
			val session = call.requireEnrolled(moderation)
			val reason = call.receive<ReasonRequest>().reason
			call.respondOk(moderation.setShadowban(session.moderator, call.stringId(), false, reason))
		}

		post("/users/{id}/reset-nickname") {
			val session = call.requireEnrolled(moderation)
			val reason = call.receive<ReasonRequest>().reason
			call.respondOk(moderation.resetNickname(session.moderator, call.stringId(), reason))
		}

		post("/users/{id}/ban-device") {
			val session = call.requireEnrolled(moderation)
			val reason = call.receive<ReasonRequest>().requireReason()
			call.respondOk(moderation.banDevice(session.moderator, call.stringId(), reason))
		}

		// -- flags ---------------------------------------------------------------------------------

		post("/queues/ban-evasion/{id}/review") {
			val session = call.requireEnrolled(moderation)
			val note = call.receive<ReasonRequest>().reason
			call.respondOk(moderation.reviewBanEvasion(session.moderator, call.longId(), note))
		}

		post("/queues/brigades/{id}/review") {
			val session = call.requireEnrolled(moderation)
			val note = call.receive<ReasonRequest>().reason
			call.respondOk(moderation.reviewBrigade(session.moderator, call.longId(), note))
		}

		post("/queues/disputes/{id}/resolve") {
			val session = call.requireEnrolled(moderation)
			val note = call.receive<ReasonRequest>().reason
			call.respondOk(moderation.resolveDispute(session.moderator, call.longId(), note))
		}

		// -- audit log -----------------------------------------------------------------------------

		get("/actions") {
			val session = call.requireEnrolled(moderation)
			val before = call.request.queryParameters["before"]?.toLongOrNull()
			call.respond(moderation.actions(session.moderator, before, call.pageSize()).map { it.toDto() })
		}

		// -- admin only ----------------------------------------------------------------------------

		get("/devices") {
			call.requireAdmin(moderation)
			call.respond(moderation.bannedDevices().map { it.toDto() })
		}

		post("/devices/{id}/unban") {
			val session = call.requireAdmin(moderation)
			call.respondOk(moderation.unbanDevice(session.moderator, call.stringId()))
		}

		get("/moderators") {
			call.requireAdmin(moderation)
			call.respond(moderation.listModerators().map { it.toDto() })
		}

		post("/moderators") {
			val session = call.requireAdmin(moderation)
			val body = call.receive<InviteRequest>()
			val role = ModeratorRole.parse(body.role) ?: throw ApiException(ApiError.BadRequest("role"))
			val created = moderation.invite(session.moderator, body.username, body.password, role)
				?: throw ApiException(ApiError.BadRequest("username"))
			call.respond(HttpStatusCode.Created, created.toDto())
		}

		patch("/moderators/{id}") {
			val session = call.requireAdmin(moderation)
			val body = call.receive<UpdateModeratorRequest>()
			val role = body.role?.let {
				ModeratorRole.parse(it) ?: throw ApiException(ApiError.BadRequest("role"))
			}
			if (!moderation.updateModerator(session.moderator, call.stringId(), role, body.disabled)) {
				// The only edit the panel refuses outright: it would leave nobody able to undo it.
				call.respond(HttpStatusCode.Conflict, ErrorDto("would_leave_no_admin"))
				return@patch
			}
			call.respond(OkResponse())
		}

		post("/moderators/{id}/reset-totp") {
			val session = call.requireAdmin(moderation)
			call.respondOk(moderation.resetTotp(session.moderator, call.stringId()))
		}

		post("/works/merge") {
			val session = call.requireAdmin(moderation)
			val body = call.receive<MergeRequest>()
			val from = body.from.toLongOrNull() ?: throw ApiException(ApiError.BadRequest("from"))
			val into = body.into.toLongOrNull() ?: throw ApiException(ApiError.BadRequest("into"))
			call.respondOk(moderation.mergeWorks(session.moderator, from, into, body.reason))
		}

		post("/works/{id}/unmerge") {
			val session = call.requireAdmin(moderation)
			val reason = call.receive<ReasonRequest>().reason
			call.respondOk(moderation.unmergeWorks(session.moderator, call.longId(), reason))
		}
	}

@Serializable
data class ErrorDto(val error: String)

private const val DISLIKE_WINDOW_HOURS = 72
private const val MIN_DISLIKES = 1

private fun ApplicationCall.pageSize(): Int =
	(request.queryParameters["limit"]?.toIntOrNull() ?: ModerationRules.DEFAULT_PAGE_SIZE)
		.coerceIn(1, ModerationRules.MAX_PAGE_SIZE)

private fun ApplicationCall.longId(): Long =
	parameters["id"]?.toLongOrNull() ?: throw ApiException(ApiError.BadRequest("id"))

private fun ApplicationCall.stringId(): String =
	parameters["id"]?.takeIf { it.isNotBlank() } ?: throw ApiException(ApiError.BadRequest("id"))

private fun ReasonRequest.requireReason(): String {
	val trimmed = reason?.trim().orEmpty()
	if (trimmed.length !in ModerationRules.MIN_REASON_LENGTH..ModerationRules.MAX_REASON_LENGTH) {
		throw ApiException(ApiError.BadRequest("reason"))
	}
	return trimmed
}

private suspend fun ApplicationCall.respondOk(succeeded: Boolean) {
	if (succeeded) respond(OkResponse()) else throw ApiException(ApiError.NotFound)
}

internal fun ApplicationCall.requireSession(moderation: ModerationService): ModSession {
	attributes.getOrNull(SessionKey)?.let { return it }
	val token = request.cookies[MOD_SESSION_COOKIE] ?: throw ApiException(ApiError.Unauthorized)
	val session = moderation.authenticate(token) ?: throw ApiException(ApiError.Unauthorized)
	if (session.moderator.isDisabled) throw ApiException(ApiError.Unauthorized)
	attributes.put(SessionKey, session)
	return session
}

/**
 * A session that has finished enrolling.
 *
 * This is where "TOTP from the start" is actually enforced: an account that has not enrolled holds a
 * valid session and every endpoint but the two enrolment ones refuses it.
 */
internal fun ApplicationCall.requireEnrolled(moderation: ModerationService): ModSession {
	val session = requireSession(moderation)
	if (session.needsTotpEnrolment) throw ApiException(ApiError.Forbidden("totp_enrolment_required"))
	return session
}

private fun ApplicationCall.requireAdmin(moderation: ModerationService): ModSession {
	val session = requireEnrolled(moderation)
	if (!session.moderator.role.isAdmin) throw ApiException(ApiError.Forbidden("admin_required"))
	return session
}
