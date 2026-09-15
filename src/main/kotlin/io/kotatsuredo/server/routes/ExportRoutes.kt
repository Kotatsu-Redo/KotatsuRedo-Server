package io.kotatsuredo.server.routes

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.enforceLimit
import io.kotatsuredo.server.auth.requireCaller
import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.ratings.RatingScale
import io.kotatsuredo.server.ratings.RatingService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class ExportedCommentDto(
	val id: String,
	@SerialName("work_id") val workId: String,
	@SerialName("chapter_id") val chapterId: String? = null,
	@SerialName("parent_id") val parentId: String? = null,
	val body: String,
	val lang: String? = null,
	@SerialName("created_at") val createdAt: String,
	@SerialName("edited_at") val editedAt: String? = null,
	/** visible | shadowed | removed. Included because a copy that omits them is not a copy. */
	val state: String,
	val up: Int,
	val down: Int,
)

@Serializable
data class ExportedRatingDto(
	@SerialName("work_id") val workId: String,
	/** Stars, 0.5 to 5. */
	val stars: Double,
	@SerialName("created_at") val createdAt: String,
	@SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ExportedVoteDto(
	@SerialName("comment_id") val commentId: String,
	/** +1 or -1. */
	val value: Int,
	@SerialName("created_at") val createdAt: String,
)

@Serializable
data class ExportDto(
	@SerialName("exported_at") val exportedAt: String,
	@SerialName("user_id") val userId: String,
	val nickname: String? = null,
	@SerialName("created_at") val createdAt: String,
	val comments: List<ExportedCommentDto>,
	val ratings: List<ExportedRatingDto>,
	val votes: List<ExportedVoteDto>,
	/**
	 * What is deliberately absent, said out loud rather than left to be noticed.
	 *
	 * Telemetry cannot appear here even in principle: its rows are keyed by a pseudonym recomputed
	 * daily from a secret the server never stores, so nobody - including this endpoint - can work out
	 * which rows were yours.
	 */
	val notes: List<String> = NOTES,
)

private val NOTES = listOf(
	"Your identity key is not here. The server only ever had its hash, so it cannot be exported - " +
		"the app's own backup is where the key lives.",
	"Telemetry is not here. It is keyed by a pseudonym recomputed daily from your key, which the " +
		"server does not store, so nobody can tell which measurements were yours.",
	"Device hashes are not here. They are one-way and peppered with a server-side secret; the " +
		"values would mean nothing and could not be checked against anything.",
)

private fun OffsetDateTime.iso(): String = DateTimeFormatter.ISO_INSTANT.format(toInstant())

/**
 * "Give me a copy of everything you hold about me."
 *
 * Self-service, authenticated by the same key as everything else, because the alternative is a
 * request nobody could verify: this server cannot identify anybody, so an access request over email
 * has no way to prove whose account is whose. Holding the key is the only proof there is, and it is
 * the same proof the delete button already accepts.
 */
fun Route.exportRoutes(
	identities: IdentityService,
	comments: CommentRepository,
	ratings: RatingService,
	limiter: RateLimiter,
) {
	get("/identity/export") {
		val caller = call.requireCaller(identities)
		call.enforceLimit(limiter, RateLimiter.Bucket.EXPORT, caller.tier, caller.identity.id)
		val identity = caller.identity

		// Named so a browser or the app saves it as a file rather than rendering it.
		call.response.header(
			HttpHeaders.ContentDisposition,
			"attachment; filename=\"kotatsu-community-${identity.id}.json\"",
		)
		call.respondTextWriter(ContentType.Application.Json) {
			write("{\"exported_at\":${exportJson.encodeToString(OffsetDateTime.now().iso())}")
			write(",\"user_id\":${exportJson.encodeToString(identity.id)}")
			write(",\"nickname\":${exportJson.encodeToString(identity.nickname)}")
			write(",\"created_at\":${exportJson.encodeToString(identity.createdAt.iso())}")

			write(",\"comments\":[")
			var first = true
			var commentCursor = 0L
			do {
				val page = comments.allByPage(identity.id, commentCursor, EXPORT_PAGE_SIZE)
				page.forEach { comment ->
					if (!first) write(",")
					first = false
					write(exportJson.encodeToString(comment.toExportDto()))
				}
				commentCursor = page.lastOrNull()?.id ?: commentCursor
			} while (page.size == EXPORT_PAGE_SIZE)

			write("],\"ratings\":[")
			first = true
			var ratingCursor = 0L
			do {
				val page = ratings.allRatingsByPage(identity.id, ratingCursor, EXPORT_PAGE_SIZE)
				page.forEach { rating ->
					if (!first) write(",")
					first = false
					write(exportJson.encodeToString(rating.toExportDto()))
				}
				ratingCursor = page.lastOrNull()?.workId ?: ratingCursor
			} while (page.size == EXPORT_PAGE_SIZE)

			write("],\"votes\":[")
			first = true
			var voteCursor = 0L
			do {
				val page = comments.votesByPage(identity.id, voteCursor, EXPORT_PAGE_SIZE)
				page.forEach { (commentId, value, castAt) ->
					if (!first) write(",")
					first = false
					write(exportJson.encodeToString(ExportedVoteDto(commentId.toString(), value, castAt.iso())))
				}
				voteCursor = page.lastOrNull()?.first ?: voteCursor
			} while (page.size == EXPORT_PAGE_SIZE)

			write("],\"notes\":${exportJson.encodeToString(NOTES)}}")
		}
	}
}

private val exportJson = Json { encodeDefaults = true }
private const val EXPORT_PAGE_SIZE = 250

private fun io.kotatsuredo.server.comments.Comment.toExportDto() = ExportedCommentDto(
	id = id.toString(),
	workId = workId.toString(),
	chapterId = chapterId?.toString(),
	parentId = parentId?.toString(),
	body = body,
	lang = lang,
	createdAt = createdAt.iso(),
	editedAt = editedAt?.iso(),
	state = state.name.lowercase(),
	up = up,
	down = down,
)

private fun io.kotatsuredo.server.ratings.ExportedRating.toExportDto() = ExportedRatingDto(
	workId = workId.toString(),
	stars = RatingScale.stars(value),
	createdAt = createdAt.iso(),
	updatedAt = updatedAt.iso(),
)
