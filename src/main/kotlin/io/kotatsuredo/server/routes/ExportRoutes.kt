package io.kotatsuredo.server.routes

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.enforceLimit
import io.kotatsuredo.server.auth.requireCaller
import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.ratings.RatingScale
import io.kotatsuredo.server.ratings.RatingService
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
		call.enforceLimit(limiter, RateLimiter.Bucket.GENERAL, caller.tier, caller.identity.id)
		val identity = caller.identity

		val export = ExportDto(
			exportedAt = OffsetDateTime.now().iso(),
			userId = identity.id,
			nickname = identity.nickname,
			createdAt = identity.createdAt.iso(),
			comments = comments.allBy(identity.id).map { comment ->
				ExportedCommentDto(
					id = comment.id.toString(),
					workId = comment.workId.toString(),
					chapterId = comment.chapterId?.toString(),
					parentId = comment.parentId?.toString(),
					body = comment.body,
					lang = comment.lang,
					createdAt = comment.createdAt.iso(),
					editedAt = comment.editedAt?.iso(),
					state = comment.state.name.lowercase(),
					up = comment.up,
					down = comment.down,
				)
			},
			ratings = ratings.allRatingsBy(identity.id).map {
				ExportedRatingDto(
					workId = it.workId.toString(),
					stars = RatingScale.stars(it.value),
					createdAt = it.createdAt.iso(),
					updatedAt = it.updatedAt.iso(),
				)
			},
			votes = comments.votesBy(identity.id).map { (commentId, value, castAt) ->
				ExportedVoteDto(commentId.toString(), value, castAt.iso())
			},
		)

		// Named so a browser or the app saves it as a file rather than rendering it.
		call.response.header(
			HttpHeaders.ContentDisposition,
			"attachment; filename=\"kotatsu-community-${identity.id}.json\"",
		)
		call.respond(export)
	}
}
