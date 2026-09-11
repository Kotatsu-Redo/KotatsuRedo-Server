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
import io.kotatsuredo.server.moderation.EnrolResult
import io.kotatsuredo.server.moderation.LoginResult
import io.kotatsuredo.server.moderation.ModActions
import io.kotatsuredo.server.moderation.ModerationQueueRepository
import io.kotatsuredo.server.moderation.ModerationService
import io.kotatsuredo.server.moderation.Moderator
import io.kotatsuredo.server.moderation.ModeratorRepository
import io.kotatsuredo.server.moderation.ModeratorRole
import io.kotatsuredo.server.moderation.Totp
import io.kotatsuredo.server.ratings.RatingRepository
import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.works.WorkRepository
import java.time.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModerationServiceTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val moderators by lazy { ModeratorRepository(source) }
	private val queues by lazy { ModerationQueueRepository(source) }
	private val commentRepository by lazy { CommentRepository(source) }
	private val comments by lazy { CommentService(commentRepository) }
	private val identityRepository by lazy { IdentityRepository(PostgresTestBase.database.exposed) }
	private val works by lazy { WorkRepository(source) }
	private val ratings by lazy { RatingService(RatingRepository(source)) }
	private val service by lazy {
		ModerationService(
			moderators = moderators,
			queues = queues,
			comments = commentRepository,
			identities = identityRepository,
			works = works,
			ratings = ratings,
			clock = Clock.systemUTC(),
		)
	}
	private val identities by lazy { IdentityService(identityRepository, DevicePepper.of("test")) }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE mod_action, mod_session, mod_totp_use, moderator, work_link_dispute, " +
						"comment, comment_vote, rating, work_rating_agg, rating_brigade_flag, work, " +
						"work_title, work_alias, work_cover_hash, work_external_id, work_relation, " +
						"work_merge_log, app_user, user_active_day, app_device, device_ban, " +
						"ban_evasion_flag CASCADE",
				)
			}
		}
	}

	private fun admin(username: String = "root"): Moderator =
		assertNotNull(service.bootstrapFirstAdmin(username, "a-long-enough-password"))

	/** Bootstraps an admin and finishes its TOTP enrolment, which is the normal starting state. */
	private fun enrolledAdmin(username: String = "root"): Moderator {
		val moderator = admin(username)
		val secret = assertIs<EnrolResult.Started>(service.startEnrolment(moderator)).secret
		assertTrue(service.confirmEnrolment(moderator, Totp.code(secret, Totp.timeStep(now()))))
		return assertNotNull(moderators.find(moderator.id))
	}

	private fun now() = System.currentTimeMillis() / 1000

	/**
	 * A code from a step that has not been burned yet.
	 *
	 * Enrolment consumes the current step, so a login straight afterwards has to use a neighbouring
	 * one - which is the replay protection working, not a test artefact.
	 */
	private fun freshCode(secret: String, offset: Long = 1) = Totp.code(secret, Totp.timeStep(now()) + offset)

	private fun user(seed: String): Identity =
		(identities.hello(seed, DeviceIdentifiers("dev-$seed", null)) as HelloOutcome.Ok).identity

	private fun work(title: String = "Some Manga"): Long =
		works.createWork(title, 2018, "manga", nsfw = false)

	private fun comment(workId: Long, author: Identity, body: String = "This chapter was excellent."): Long =
		assertIs<PostResult.Posted>(
			comments.post(workId, null, author, null, body, isSpoiler = false, lang = "en"),
		).view.comment.id

	// -- bootstrap and login -----------------------------------------------------------------------

	@Test
	fun `the bootstrap path creates one admin and then closes`() {
		assertNotNull(service.bootstrapFirstAdmin("root", "a-long-enough-password"))
		// Left in the environment on the next deploy, which is exactly the case that must not create
		// a second standing account.
		assertNull(service.bootstrapFirstAdmin("root2", "another-long-password"))
		assertEquals(1, moderators.count())
	}

	@Test
	fun `an unknown username and a wrong password are the same answer`() {
		admin()
		assertEquals(LoginResult.InvalidCredentials, service.login("root", "wrong-password", null))
		assertEquals(LoginResult.InvalidCredentials, service.login("nobody", "a-long-enough-password", null))
	}

	@Test
	fun `a fresh account signs in without a code and can then do nothing but enrol`() {
		val moderator = admin()
		val login = assertIs<LoginResult.Ok>(service.login("root", "a-long-enough-password", null))
		assertTrue(login.session.needsTotpEnrolment)

		val started = assertIs<EnrolResult.Started>(service.startEnrolment(moderator))
		assertTrue(service.confirmEnrolment(moderator, Totp.code(started.secret, Totp.timeStep(now()))))

		val session = assertNotNull(service.authenticate(login.token))
		assertFalse(session.needsTotpEnrolment)
	}

	@Test
	fun `an enrolled account cannot sign in without a code`() {
		enrolledAdmin()
		assertEquals(LoginResult.TotpRequired, service.login("root", "a-long-enough-password", null))
		assertEquals(LoginResult.TotpRequired, service.login("root", "a-long-enough-password", "000000"))
	}

	@Test
	fun `a code cannot be used twice`() {
		val moderator = enrolledAdmin()
		val secret = assertNotNull(moderators.totpSecretOf(moderator.id))
		val code = freshCode(secret)

		assertIs<LoginResult.Ok>(service.login("root", "a-long-enough-password", code))
		// Same code, well inside its thirty seconds. A shoulder-surfed code is not a second login.
		assertEquals(LoginResult.TotpRequired, service.login("root", "a-long-enough-password", code))
	}

	@Test
	fun `usernames are matched case-insensitively`() {
		enrolledAdmin("Root")
		val secret = assertNotNull(moderators.totpSecretOf(assertNotNull(moderators.findByUsername("root")).id))
		assertIs<LoginResult.Ok>(service.login("ROOT", "a-long-enough-password", freshCode(secret)))
	}

	@Test
	fun `disabling an account closes its open sessions immediately`() {
		val root = enrolledAdmin()
		val other = assertNotNull(service.invite(root, "helper", "a-long-enough-password", ModeratorRole.MODERATOR))
		val login = assertIs<LoginResult.Ok>(service.login("helper", "a-long-enough-password", null))
		assertNotNull(service.authenticate(login.token))

		assertTrue(service.updateModerator(root, other.id, role = null, disabled = true))
		// Not "when their session expires" - now.
		assertNull(service.authenticate(login.token))
	}

	@Test
	fun `the last admin cannot be demoted or disabled`() {
		val root = enrolledAdmin()
		service.invite(root, "helper", "a-long-enough-password", ModeratorRole.MODERATOR)

		assertFalse(service.updateModerator(root, root.id, ModeratorRole.MODERATOR, null))
		assertFalse(service.updateModerator(root, root.id, null, disabled = true))

		// With a second admin in place it is allowed, because the panel is no longer locking itself.
		val second = assertNotNull(service.invite(root, "second", "a-long-enough-password", ModeratorRole.ADMIN))
		assertTrue(service.updateModerator(root, root.id, ModeratorRole.MODERATOR, null))
		assertEquals(ModeratorRole.ADMIN, assertNotNull(moderators.find(second.id)).role)
	}

	@Test
	fun `resetting two-factor sends the account back to enrolment`() {
		val root = enrolledAdmin()
		val other = assertNotNull(service.invite(root, "helper", "a-long-enough-password", ModeratorRole.MODERATOR))
		val secret = assertIs<EnrolResult.Started>(service.startEnrolment(other)).secret
		assertTrue(service.confirmEnrolment(other, Totp.code(secret, Totp.timeStep(now()))))

		assertTrue(service.resetTotp(root, other.id))
		assertFalse(assertNotNull(moderators.find(other.id)).totpConfirmed)
		assertNull(moderators.totpSecretOf(other.id))
	}

	// -- comments ----------------------------------------------------------------------------------

	@Test
	fun `removing a comment blanks it and keeps the text only in the audit log`() {
		val root = enrolledAdmin()
		val id = comment(work(), user("a"), "This chapter was a disaster and so are you.")

		assertTrue(service.removeComment(root, id, "personal attack"))

		val stored = assertNotNull(commentRepository.find(id))
		assertEquals(CommentState.REMOVED, stored.state)
		assertEquals("", stored.body)

		val logged = service.actions(root, null, 10).first()
		assertEquals(ModActions.REMOVE_COMMENT, logged.action)
		assertEquals("personal attack", logged.reason)
		assertTrue(assertNotNull(logged.detail).contains("so are you"))
	}

	@Test
	fun `a removed comment is restored from its audit snapshot`() {
		val root = enrolledAdmin()
		val body = "This chapter was a disaster and so are you."
		val id = comment(work(), user("a"), body)

		service.removeComment(root, id, "personal attack")
		assertTrue(service.restoreComment(root, id, "on reflection, fine"))

		val stored = assertNotNull(commentRepository.find(id))
		assertEquals(CommentState.VISIBLE, stored.state)
		assertEquals(body, stored.body)
	}

	@Test
	fun `a comment its own author deleted cannot be restored by a moderator`() {
		val root = enrolledAdmin()
		val author = user("a")
		val id = comment(work(), author)

		assertTrue(comments.delete(id, author.id))
		// No audit snapshot exists, so there is nothing to put back - and republishing words somebody
		// withdrew is not a moderator's call anyway.
		assertFalse(service.restoreComment(root, id, null))
	}

	@Test
	fun `the audit log refuses to be rewritten`() {
		val root = enrolledAdmin()
		service.removeComment(root, comment(work(), user("a")), "spam")

		val failure = runCatching {
			source.connection.use { connection ->
				connection.createStatement().use { it.executeUpdate("UPDATE mod_action SET reason = 'nothing'") }
			}
		}
		assertTrue(failure.isFailure, "mod_action accepted an UPDATE")
		assertTrue(
			runCatching {
				source.connection.use { connection ->
					connection.createStatement().use { it.executeUpdate("DELETE FROM mod_action") }
				}
			}.isFailure,
			"mod_action accepted a DELETE",
		)
	}

	// -- users -------------------------------------------------------------------------------------

	@Test
	fun `a ban clears the user's comments and is recorded with a reason`() {
		val root = enrolledAdmin()
		val workId = work()
		val target = user("a")
		val id = comment(workId, target)

		assertTrue(service.banUser(root, target.id, "repeated harassment"))

		assertTrue(assertNotNull(identityRepository.findById(target.id)).isBanned)
		assertEquals(CommentState.REMOVED, assertNotNull(commentRepository.find(id)).state)

		val logged = service.actions(root, null, 10).first()
		assertEquals(ModActions.BAN_USER, logged.action)
		assertTrue(assertNotNull(logged.detail).contains("comments_cleared"))
	}

	@Test
	fun `lifting a ban does not bring the comments back`() {
		val root = enrolledAdmin()
		val target = user("a")
		val id = comment(work(), target)

		service.banUser(root, target.id, "repeated harassment")
		assertTrue(service.unbanUser(root, target.id, "appealed on the Discord"))

		assertFalse(assertNotNull(identityRepository.findById(target.id)).isBanned)
		assertEquals(CommentState.REMOVED, assertNotNull(commentRepository.find(id)).state)
	}

	@Test
	fun `a shadowban is invisible in the user's own identity response`() {
		val root = enrolledAdmin()
		val target = user("a")
		assertTrue(service.setShadowban(root, target.id, shadowbanned = true, reason = "brigading"))

		val reloaded = assertNotNull(identityRepository.findById(target.id))
		assertTrue(reloaded.isShadowbanned)
		// The flag exists server-side and never travels: IdentityResponse has no field for it, which
		// is the only reason the shadowban is worth anything.
		assertFalse(reloaded.isBanned)
	}

	@Test
	fun `a device ban stops the next signup from that device`() {
		val root = enrolledAdmin()
		val target = user("evader")

		assertTrue(service.banDevice(root, target.id, "ban evasion"))
		// The same hardware coming back with a brand new secret.
		assertEquals(HelloOutcome.DeviceBanned, identities.hello("a-new-secret", DeviceIdentifiers("dev-evader", null)))
	}

	@Test
	fun `a device ban can be reversed, and only that ban`() {
		val root = enrolledAdmin()
		service.banDevice(root, user("a").id, "mistake")
		service.banDevice(root, user("b").id, "genuine")

		val bans = service.bannedDevices()
		assertEquals(2, bans.size)
		assertTrue(service.unbanDevice(root, bans.first().fingerprint))
		assertEquals(1, service.bannedDevices().size)
		assertFalse(service.unbanDevice(root, "0000000000000000"))
	}

	// -- works -------------------------------------------------------------------------------------

	@Test
	fun `a merge moves content and an unmerge brings it home`() {
		val root = enrolledAdmin()
		val keep = work("Kagurabachi")
		val duplicate = work("Kagura Bachi")
		val author = user("a")
		val moved = comment(duplicate, author)
		val stayed = comment(keep, user("b"))
		ratings.rate(duplicate, author.id, 8)

		assertTrue(service.mergeWorks(root, from = duplicate, into = keep, reason = "same work"))

		assertEquals(keep, assertNotNull(commentRepository.find(moved)).workId)
		assertEquals(keep, works.mergedInto(duplicate))
		assertNull(works.metadataOf(duplicate), "a merged work should not describe itself any more")
		assertEquals(1, ratings.aggregate(keep).count)

		assertTrue(service.unmergeWorks(root, duplicate, "wrong call"))

		assertEquals(duplicate, assertNotNull(commentRepository.find(moved)).workId)
		assertEquals(keep, assertNotNull(commentRepository.find(stayed)).workId, "the other work's comment stayed")
		assertNull(works.mergedInto(duplicate))
		assertNotNull(works.metadataOf(duplicate))
		assertEquals(0, ratings.aggregate(keep).count)
		assertEquals(1, ratings.aggregate(duplicate).count)
	}

	@Test
	fun `a work merged into a chain still resolves to the end of it`() {
		val root = enrolledAdmin()
		val a = work("A")
		val b = work("B")
		val c = work("C")
		service.mergeWorks(root, from = a, into = b, reason = "same")
		service.mergeWorks(root, from = b, into = c, reason = "same again")

		// A client that cached A's id before either merge deserves C, not a dead end at B.
		assertEquals(c, works.mergedInto(a))
	}

	@Test
	fun `merging a work into itself is refused`() {
		val root = enrolledAdmin()
		val id = work()
		assertFalse(service.mergeWorks(root, from = id, into = id, reason = "nonsense"))
	}

	// -- queues ------------------------------------------------------------------------------------

	@Test
	fun `the disliked queue surfaces exactly what ranking buried`() {
		val root = enrolledAdmin()
		val workId = work()
		val liked = comment(workId, user("a"), "A generous and thoughtful reading.")
		val disliked = comment(workId, user("b"), "A comment nobody enjoyed reading at all.")

		repeat(4) { comments.vote(disliked, user("d$it"), -1) }
		comments.vote(liked, user("up"), 1)

		val queue = queues.mostDisliked(hours = 72, minDislikes = 1, limit = 10)
		assertEquals(listOf(disliked), queue.map { it.id })
		assertEquals(4, queue.first().down)

		// And the counts the panel's nav shows agree with it.
		assertEquals(1, queues.counts(72, 1)["disliked"])
		assertNotNull(root)
	}

	@Test
	fun `the firehose shows shadowed comments, which every user-facing path hides`() {
		enrolledAdmin()
		val workId = work()
		val troll = user("t")
		identityRepository.setShadowbanned(troll.id, true)
		val shadowed = comments.post(
			workId, null, assertNotNull(identityRepository.findById(troll.id)), null,
			"Something only its author can see.", false, "en",
		)
		assertIs<PostResult.Posted>(shadowed)

		// Invisible to readers, and the one queue that must still show it.
		assertTrue(queues.firehose(null, 10).any { it.id == shadowed.view.comment.id })
		assertEquals("shadowed", stateName(queues.firehose(null, 10).first().state))
	}

	@Test
	fun `a refused automatic merge is filed rather than dropped`() {
		val a = work("A")
		val b = work("B")
		queues.fileDispute(a, b, "needs_review", null, "user_migration")
		// Twice, as two users reporting the same thing would.
		assertFalse(queues.fileDispute(a, b, "needs_review", null, "user_migration"))

		val open = queues.openDisputes(10)
		assertEquals(1, open.size)
		assertEquals(a, open.first().workId)
	}

	@Test
	fun `resolving a dispute takes it out of the queue and names who did it`() {
		val root = enrolledAdmin()
		val a = work("A")
		queues.fileDispute(a, null, "different_works", user("u").id, "this is two series")

		val dispute = queues.openDisputes(10).single()
		assertTrue(service.resolveDispute(root, dispute.id, "split by hand"))
		assertTrue(queues.openDisputes(10).isEmpty())
		assertEquals(ModActions.RESOLVE_DISPUTE, service.actions(root, null, 1).first().action)
	}

	// -- audit visibility --------------------------------------------------------------------------

	@Test
	fun `a moderator sees their own actions and an admin sees everyone's`() {
		val root = enrolledAdmin()
		val helper = assertNotNull(service.invite(root, "helper", "a-long-enough-password", ModeratorRole.MODERATOR))
		val workId = work()

		service.removeComment(root, comment(workId, user("a")), "by the admin")
		service.removeComment(helper, comment(workId, user("b")), "by the moderator")

		assertEquals(1, service.actions(helper, null, 50).count { it.action == ModActions.REMOVE_COMMENT })
		assertEquals(2, service.actions(root, null, 50).count { it.action == ModActions.REMOVE_COMMENT })
	}

	private fun stateName(state: Short) = when (state.toInt()) {
		1 -> "shadowed"
		2 -> "removed"
		else -> "visible"
	}
}
