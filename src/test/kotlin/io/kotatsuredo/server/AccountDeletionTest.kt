package io.kotatsuredo.server

import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.comments.CommentService
import io.kotatsuredo.server.comments.CommentState
import io.kotatsuredo.server.comments.PostResult
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.Identity
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.ratings.RatingRepository
import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.works.WorkRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * "Delete everything about me" (PLAN.md §6), and specifically what it must *not* take with it.
 *
 * The privacy notice promises ratings erased, comments tombstoned and the row dropped. Two of those
 * were true. The third was not: `comment.user_id` cascaded, and `comment.parent_id` cascaded after
 * it, so deleting an account deleted every reply anyone had ever written underneath it.
 */
class AccountDeletionTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val comments by lazy { CommentRepository(source) }
	private val service by lazy { CommentService(comments) }
	private val identityRepository by lazy { IdentityRepository(PostgresTestBase.database.exposed, source) }
	private val identities by lazy {
		IdentityService(
			repository = identityRepository,
			pepper = DevicePepper.of("test"),
		)
	}
	private val works by lazy { WorkRepository(source) }
	private val ratings by lazy { RatingService(RatingRepository(source)) }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE comment, comment_vote, rating, work_rating_agg, work, work_title, " +
						"work_alias, work_cover_hash, work_external_id, work_relation, work_merge_log, " +
						"app_user, user_active_day, app_device, device_ban, ban_evasion_flag CASCADE",
				)
			}
		}
	}

	private fun user(seed: String): Identity =
		(identities.hello(seed, DeviceIdentifiers("dev-$seed", null)) as HelloOutcome.Ok).identity

	@Test
	fun `deleting an account does not delete other people's replies`() {
		val workId = works.createWork("Some Manga", 2018, "manga", nsfw = false)
		val leaving = user("leaving")
		val other = user("other")

		val root = assertIs<PostResult.Posted>(
			service.post(workId, null, leaving, null, "I have a lot of thoughts about this.", false, "en"),
		).view.comment
		val reply = assertIs<PostResult.Posted>(
			service.post(workId, null, other, root.id, "And here are mine in response.", false, "en"),
		).view.comment
		ratings.rate(workId, leaving.id, 8)

		identities.deleteEverything(leaving.id)

		// The account is gone, and so is everything it said.
		assertNull(identityRepository.findById(leaving.id))
		assertEquals(0, ratings.aggregate(workId).count, "ratings should be erased")

		val tombstone = assertNotNull(comments.find(root.id), "the comment row should survive as a tombstone")
		assertEquals(CommentState.REMOVED, tombstone.state)
		assertEquals("", tombstone.body, "the text should be gone")
		assertNull(tombstone.userId, "nothing should still point at the deleted account")

		// And somebody else's words are still theirs.
		val survivor = assertNotNull(comments.find(reply.id), "another user's reply was deleted")
		assertEquals(CommentState.VISIBLE, survivor.state)
		assertEquals("And here are mine in response.", survivor.body)
		assertEquals(other.id, survivor.userId)
	}
}
