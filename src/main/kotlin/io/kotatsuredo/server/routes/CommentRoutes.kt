package io.kotatsuredo.server.routes

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.enforceLimit
import io.kotatsuredo.server.auth.requireCaller
import io.kotatsuredo.server.comments.CommentRules
import io.kotatsuredo.server.comments.CommentService
import io.kotatsuredo.server.comments.CommentState
import io.kotatsuredo.server.comments.CommentView
import io.kotatsuredo.server.comments.EditResult
import io.kotatsuredo.server.comments.PostResult
import io.kotatsuredo.server.comments.ReplyNotification
import io.kotatsuredo.server.filter.FilterRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.works.WorkRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Serializable
data class CommentDto(
	val id: String,
	@SerialName("work_id") val workId: String,
	@SerialName("chapter_id") val chapterId: String? = null,
	@SerialName("parent_id") val parentId: String? = null,
	val depth: Int,
	/** `nickname#1234`, or empty on a tombstone. */
	val author: String,
	val body: String,
	@SerialName("is_spoiler") val isSpoiler: Boolean,
	val lang: String? = null,
	@SerialName("created_at") val createdAt: String,
	val up: Int,
	val down: Int,
	/** Wilson lower bound. The server already sorted by it; it is here for the client's own cache. */
	val score: Double,
	@SerialName("my_vote") val myVote: Int,
	@SerialName("is_mine") val isMine: Boolean,
	/**
	 * A deleted comment still occupies its place in the thread so the replies below it make sense.
	 * Everything else about it is blank.
	 */
	val deleted: Boolean = false,
)

@Serializable
data class CommentPageResponse(
	val comments: List<CommentDto>,
	/** Visible comments on this work or chapter in total, for the "N comments" label. */
	val total: Int,
	/**
	 * Visible comments per language. Threads are not unioned across languages, so this is how the app
	 * can say "also 12 in Spanish" instead of pretending the other languages are not there.
	 */
	@SerialName("by_language") val byLanguage: Map<String, Int> = emptyMap(),
)

@Serializable
data class PostCommentRequest(
	val body: String,
	@SerialName("parent_id") val parentId: String? = null,
	@SerialName("is_spoiler") val isSpoiler: Boolean = false,
	/** The app's UI language. Only the subtag is kept; see CommentService.normalizeLang. */
	val lang: String? = null,
)

@Serializable
data class EditCommentRequest(
	val body: String,
	val lang: String? = null,
)

@Serializable
data class VoteRequest(
	/** +1, -1, or 0 to withdraw. */
	val value: Int,
)

@Serializable
data class NotificationDto(
	@SerialName("comment_id") val commentId: String,
	@SerialName("work_id") val workId: String,
	@SerialName("chapter_id") val chapterId: String? = null,
	@SerialName("parent_id") val parentId: String? = null,
	val author: String,
	/** Trimmed to a notification-sized preview; the app opens the thread for the rest. */
	val preview: String,
	@SerialName("created_at") val createdAt: String,
)

@Serializable
data class NotificationsResponse(
	val replies: List<NotificationDto>,
	/**
	 * Pass this back as `since` next time. The server keeps no record of what it has delivered, so
	 * this value is the only thing preventing a notification being shown twice (PLAN.md §6).
	 */
	val cursor: String,
)

/**
 * Timestamps go out in UTC (`...Z`) rather than with a numeric offset, because the notification
 * cursor is handed straight back as a query parameter and a `+02:00` offset would arrive decoded as
 * a space.
 */
private fun OffsetDateTime.iso(): String =
	DateTimeFormatter.ISO_INSTANT.format(toInstant())

private fun CommentView.toDto(): CommentDto {
	val deleted = comment.state == CommentState.REMOVED
	return CommentDto(
		id = comment.id.toString(),
		workId = comment.workId.toString(),
		chapterId = comment.chapterId?.toString(),
		parentId = comment.parentId?.toString(),
		depth = comment.depth,
		author = authorName,
		body = comment.body,
		isSpoiler = comment.isSpoiler,
		lang = comment.lang,
		createdAt = comment.createdAt.iso(),
		up = comment.up,
		down = comment.down,
		score = comment.score,
		myVote = myVote,
		isMine = isMine,
		deleted = deleted,
	)
}

private fun ReplyNotification.toDto() = NotificationDto(
	commentId = comment.id.toString(),
	workId = comment.workId.toString(),
	chapterId = comment.chapterId?.toString(),
	parentId = comment.parentId?.toString(),
	author = authorName,
	preview = comment.body.take(PREVIEW_LENGTH),
	createdAt = comment.createdAt.iso(),
)

private const val PREVIEW_LENGTH = 140

/**
 * Comments live under the work, because that is how they are read: the details screen asks for the
 * work's comments and the reader asks for the chapter's (PLAN.md §4).
 *
 * `chapter` is an opaque number the client supplies. Until M2c aligns chapters across sources it is
 * the chapter number scaled by 100 (so 12.5 is 1250), which is stable for any source numbering its
 * chapters the same way - and wrong for the ones that do not, which is exactly the problem M2c
 * exists to solve.
 */
fun Route.commentRoutes(
	identities: IdentityService,
	comments: CommentService,
	works: WorkRepository,
	limiter: RateLimiter,
	rulesUrl: String,
	filters: FilterRepository? = null,
) {
	route("/works/{id}/comments") {

		get {
			val caller = call.requireCaller(identities)
			call.enforceLimit(limiter, RateLimiter.Bucket.GENERAL, caller.tier, caller.identity.id)

			val workId = call.resolveWorkId(works)
			val chapterId = call.chapterId()
			val page = comments.thread(
				workId = workId,
				chapterId = chapterId,
				viewer = caller.identity,
				// "top" by default: with dislikes as the only moderation signal, the thing that sinks
				// a bad comment has to be the thing that orders the list.
				sortByScore = call.request.queryParameters["sort"] != "new",
				limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: CommentRules.DEFAULT_PAGE_SIZE,
				offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0,
				// Absent means "everything": the app sends its own language, and a moderator or a
				// curious reader asking without one should not silently get a filtered view.
				lang = call.request.queryParameters["lang"],
			)
			call.respond(
				CommentPageResponse(
					comments = page.map { it.toDto() },
					total = comments.count(workId, chapterId),
					byLanguage = comments.countsByLanguage(workId, chapterId),
				),
			)
		}

		post {
			val caller = call.requireCaller(identities)
			val body = call.receive<PostCommentRequest>()
			val parentId = body.parentId?.toLongOrNull()
			if (body.parentId != null && parentId == null) throw ApiException(ApiError.BadRequest("parent_id"))

			call.enforceLimit(limiter, RateLimiter.Bucket.COMMENTS, caller.tier, caller.identity.id)
			call.enforceLimit(limiter, RateLimiter.Bucket.COMMENTS_DAILY, caller.tier, caller.identity.id)
			if (parentId != null) {
				// A separate, tighter bucket: the hourly comment allowance is meant to be spent across
				// the app, not on one argument.
				call.enforceLimit(limiter, RateLimiter.Bucket.THREAD_REPLIES, caller.tier, caller.identity.id)
			}

			val workId = call.resolveWorkId(works)
			val result = comments.post(
				workId = workId,
				chapterId = call.chapterId(),
				author = caller.identity,
				parentId = parentId,
				rawBody = body.body,
				isSpoiler = body.isSpoiler,
				lang = body.lang,
			)
			when (result) {
				is PostResult.Posted -> call.respond(HttpStatusCode.Created, result.view.toDto())
				is PostResult.TooShort -> throw ApiException(ApiError.TooShort(result.minimum))
				PostResult.TooLong -> throw ApiException(ApiError.BadRequest("body"))
				is PostResult.Blocked -> throw ApiException(
					ApiError.FilterBlocked(result.term, result.tier, rulesUrl, result.blockId?.toString()),
				)

				PostResult.ChainDepthExceeded -> throw ApiException(ApiError.ChainDepthExceeded)
				PostResult.ParentNotFound -> throw ApiException(ApiError.NotFound)
			}
		}
	}

	route("/comments/{cid}") {

		patch {
			val caller = call.requireCaller(identities)
			val commentId = call.commentId()
			val body = call.receive<EditCommentRequest>()

			when (val result = comments.edit(commentId, caller.identity, body.body, body.lang)) {
				is EditResult.Edited -> call.respond(result.view.toDto())
				is EditResult.TooShort -> throw ApiException(ApiError.TooShort(result.minimum))
				EditResult.TooLong -> throw ApiException(ApiError.BadRequest("body"))
				is EditResult.Blocked -> throw ApiException(
					ApiError.FilterBlocked(result.term, result.tier, rulesUrl, result.blockId?.toString()),
				)

				// Not yours, or too late. Deliberately the same answer as "no such comment", so this
				// cannot be used to probe for shadowed comments.
				EditResult.NotEditable, EditResult.NotFound -> throw ApiException(ApiError.NotFound)
			}
		}

		delete {
			val caller = call.requireCaller(identities)
			if (!comments.delete(call.commentId(), caller.identity.id)) {
				throw ApiException(ApiError.NotFound)
			}
			call.respond(HttpStatusCode.NoContent)
		}

		put("/vote") {
			val caller = call.requireCaller(identities)
			call.enforceLimit(limiter, RateLimiter.Bucket.VOTES, caller.tier, caller.identity.id)

			val value = call.receive<VoteRequest>().value
			if (value !in -1..1) throw ApiException(ApiError.BadRequest("value"))

			val view = comments.vote(call.commentId(), caller.identity, value)
				?: throw ApiException(ApiError.NotFound)
			call.respond(view.toDto())
		}
	}

	/**
	 * Replies to the caller's comments.
	 *
	 * Delivered once: the client stores [NotificationsResponse.cursor] and sends it back as `since`.
	 * The server holds no per-user delivery state, so a client that never sends the cursor gets the
	 * last thirty days every time - annoying, and much better than a table recording who was told
	 * what and when.
	 */
	get("/notifications") {
		val caller = call.requireCaller(identities)
		call.enforceLimit(limiter, RateLimiter.Bucket.GENERAL, caller.tier, caller.identity.id)

		val since = call.request.queryParameters["since"]?.let { raw ->
			try {
				OffsetDateTime.parse(raw)
			} catch (e: DateTimeParseException) {
				throw ApiException(ApiError.BadRequest("since"))
			}
		}
		val replies = comments.notifications(
			userId = caller.identity.id,
			since = since,
			limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: CommentRules.DEFAULT_PAGE_SIZE,
		)
		call.respond(
			NotificationsResponse(
				replies = replies.map { it.toDto() },
				// The newest reply seen, or the caller's own cursor when there was nothing new - never
				// "now", which would skip anything posted while this request was in flight.
				cursor = (replies.firstOrNull()?.comment?.createdAt ?: since ?: OffsetDateTime.now())
					.iso(),
			),
		)
	}

	/**
	 * "This was wrong."
	 *
	 * One tap from the rejection dialog, by the person who just saw exactly which term stopped them -
	 * which makes it the cheapest and best-targeted false-positive signal available. It feeds the
	 * per-rule rate that eventually demotes a misfiring rule on its own (PLAN.md §6).
	 */
	post("/filter/blocks/{id}/dispute") {
		val caller = call.requireCaller(identities)
		call.enforceLimit(limiter, RateLimiter.Bucket.GENERAL, caller.tier, caller.identity.id)

		val blockId = call.parameters["id"]?.toLongOrNull() ?: throw ApiException(ApiError.BadRequest("id"))
		// Only their own block, and only once: this signal demotes rules, so letting one person press
		// it repeatedly would hand them a way to switch the filter off.
		if (filters?.dispute(blockId, caller.identity.id) != true) throw ApiException(ApiError.NotFound)
		call.respond(HttpStatusCode.Accepted, DisputeAccepted())
	}
}

@Serializable
data class DisputeAccepted(val ok: Boolean = true)

private fun ApplicationCall.chapterId(): Long? = request.queryParameters["chapter"]?.let {
	it.toLongOrNull() ?: throw ApiException(ApiError.BadRequest("chapter"))
}

private fun ApplicationCall.commentId(): Long =
	parameters["cid"]?.toLongOrNull() ?: throw ApiException(ApiError.BadRequest("cid"))
