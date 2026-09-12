package io.kotatsuredo.server

import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.comments.CommentRules
import io.kotatsuredo.server.comments.CommentService
import io.kotatsuredo.server.comments.CommentState
import io.kotatsuredo.server.comments.ContentFilter
import io.kotatsuredo.server.comments.EditResult
import io.kotatsuredo.server.comments.PostResult
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.Identity
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.works.WorkRepository
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommentServiceTest {

	private val repository by lazy { CommentRepository(PostgresTestBase.database.source) }
	private val service by lazy { CommentService(repository) }
	private val works by lazy { WorkRepository(PostgresTestBase.database.source) }
	private val identities by lazy {
		IdentityService(IdentityRepository(PostgresTestBase.database.exposed), DevicePepper.of("test"))
	}

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE comment, comment_vote, rating, work_rating_agg, rating_brigade_flag, " +
						"work, work_title, work_alias, work_cover_hash, work_external_id, work_relation, " +
						"work_merge_log, app_user, user_active_day, app_device, device_ban, " +
						"ban_evasion_flag CASCADE",
				)
			}
		}
	}

	private fun work(title: String = "Some Manga"): Long =
		works.createWork(title, 2018, "manga", nsfw = false)

	private fun user(seed: String): Identity {
		val outcome = identities.hello(seed, DeviceIdentifiers("dev-$seed", null))
		return (outcome as HelloOutcome.Ok).identity
	}

	private fun shadowban(userId: String) {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement("UPDATE app_user SET is_shadowbanned = TRUE WHERE id = ?")
				.use { statement ->
					statement.setString(1, userId)
					statement.executeUpdate()
				}
		}
	}

	/** Long enough to clear [CommentRules.MIN_BODY_LENGTH] without every test spelling one out. */
	private fun text(marker: String = "") = "This chapter was genuinely excellent $marker".trim()

	private fun post(
		workId: Long,
		author: Identity,
		body: String = text(),
		parentId: Long? = null,
		chapterId: Long? = null,
	) = service.post(workId, chapterId, author, parentId, body, isSpoiler = false, lang = "en")

	// -- posting ---------------------------------------------------------------------------------

	@Test
	fun `a comment is stored and comes back visible`() {
		val workId = work()
		val author = user("a")
		val result = assertIs<PostResult.Posted>(post(workId, author))

		assertEquals(CommentState.VISIBLE, result.view.comment.state)
		assertTrue(result.view.isMine)
		assertEquals(1, service.count(workId, null))
		assertEquals(1, service.thread(workId, null, user("b"), sortByScore = true, limit = 25, offset = 0).size)
	}

	@Test
	fun `a one word comment is refused`() {
		val result = post(work(), user("a"), body = "first")
		assertEquals(CommentRules.MIN_BODY_LENGTH, assertIs<PostResult.TooShort>(result).minimum)
	}

	@Test
	fun `length is counted in characters a person would recognise`() {
		// Nine emoji: nine characters to a reader, eighteen to `String.length`. Counting the code
		// units would let this through as though it cleared a ten-character minimum.
		val result = post(work(), user("a"), body = "😀".repeat(9))
		assertIs<PostResult.TooShort>(result)
	}

	@Test
	fun `a blocked term is named in the rejection`() {
		val blocking = CommentService(
			repository,
			filter = { body, _, _ ->
				if ("badword" in body) {
					ContentFilter.Verdict.Blocked("badword", "profanity")
				} else {
					ContentFilter.Verdict.Allowed
				}
			},
		)
		val result = blocking.post(
			work(), null, user("a"), null, "this chapter was badword awful", false, "en",
		)
		assertEquals("badword", assertIs<PostResult.Blocked>(result).term)
	}

	@Test
	fun `a reply to another work is refused`() {
		val root = assertIs<PostResult.Posted>(post(work("A"), user("a"))).view.comment
		val elsewhere = post(work("B"), user("b"), parentId = root.id)
		assertEquals(PostResult.ParentNotFound, elsewhere)
	}

	// -- the chain rule --------------------------------------------------------------------------

	@Test
	fun `two people get three rounds each and no more`() {
		val workId = work()
		val a = user("a")
		val b = user("b")

		var parent = assertIs<PostResult.Posted>(post(workId, a, text("1"))).view.comment.id
		repeat(CommentRules.MAX_CHAIN_ROUNDS - 1) { round ->
			parent = assertIs<PostResult.Posted>(post(workId, b, text("b$round"), parent)).view.comment.id
			parent = assertIs<PostResult.Posted>(post(workId, a, text("a$round"), parent)).view.comment.id
		}
		// b has replied twice, a has posted three times. b's third is fine; a's fourth is not.
		parent = assertIs<PostResult.Posted>(post(workId, b, text("b-last"), parent)).view.comment.id
		assertEquals(PostResult.ChainDepthExceeded, post(workId, a, text("a-too-many"), parent))
	}

	@Test
	fun `a third voice turns a duel back into a conversation`() {
		val workId = work()
		val a = user("a")
		val b = user("b")
		val c = user("c")

		var parent = assertIs<PostResult.Posted>(post(workId, a, text("1"))).view.comment.id
		repeat(CommentRules.MAX_CHAIN_ROUNDS - 1) { round ->
			parent = assertIs<PostResult.Posted>(post(workId, b, text("b$round"), parent)).view.comment.id
			parent = assertIs<PostResult.Posted>(post(workId, a, text("a$round"), parent)).view.comment.id
		}
		parent = assertIs<PostResult.Posted>(post(workId, b, text("b-last"), parent)).view.comment.id

		// Same position that was refused above, except someone else has spoken since.
		parent = assertIs<PostResult.Posted>(post(workId, c, text("c"), parent)).view.comment.id
		assertIs<PostResult.Posted>(post(workId, a, text("a-again"), parent))
	}

	// -- shadowbans ------------------------------------------------------------------------------

	@Test
	fun `a shadowed comment is invisible to everyone but its author`() {
		val workId = work()
		val author = user("a")
		shadowban(author.id)
		val shadowed = author.copy(isShadowbanned = true)

		val posted = assertIs<PostResult.Posted>(post(workId, shadowed))
		// Nothing in the response says so.
		assertEquals(CommentState.SHADOWED, posted.view.comment.state)

		val mine = service.thread(workId, null, shadowed, sortByScore = true, limit = 25, offset = 0)
		assertEquals(1, mine.size)

		val theirs = service.thread(workId, null, user("b"), sortByScore = true, limit = 25, offset = 0)
		assertTrue(theirs.isEmpty())
		assertEquals(0, service.count(workId, null))
	}

	@Test
	fun `a shadowed reply does not notify the person it replies to`() {
		val workId = work()
		val target = user("a")
		val troll = user("t")
		shadowban(troll.id)

		val root = assertIs<PostResult.Posted>(post(workId, target)).view.comment
		assertIs<PostResult.Posted>(post(workId, troll.copy(isShadowbanned = true), text("r"), root.id))

		assertTrue(service.notifications(target.id, null, 25).isEmpty())
	}

	// -- votes -----------------------------------------------------------------------------------

	@Test
	fun `votes are recounted rather than incremented`() {
		val workId = work()
		val root = assertIs<PostResult.Posted>(post(workId, user("a"))).view.comment
		val voter = user("b")

		service.vote(root.id, voter, 1)
		// Sent twice, as a flaky connection would.
		val view = service.vote(root.id, voter, 1)
		assertNotNull(view)
		assertEquals(1, view.comment.up)
		assertEquals(1, view.myVote)

		val changed = service.vote(root.id, voter, -1)
		assertNotNull(changed)
		assertEquals(0, changed.comment.up)
		assertEquals(1, changed.comment.down)

		val withdrawn = service.vote(root.id, voter, 0)
		assertNotNull(withdrawn)
		assertEquals(0, withdrawn.comment.up)
		assertEquals(0, withdrawn.comment.down)
		assertEquals(0, withdrawn.myVote)
	}

	@Test
	fun `voting on your own comment is refused`() {
		val workId = work()
		val author = user("a")
		val root = assertIs<PostResult.Posted>(post(workId, author)).view.comment
		assertNull(service.vote(root.id, author, 1))
	}

	@Test
	fun `a shadowbanned voter cannot move the score`() {
		val workId = work()
		val root = assertIs<PostResult.Posted>(post(workId, user("a"))).view.comment
		val troll = user("t")
		shadowban(troll.id)

		service.vote(root.id, troll.copy(isShadowbanned = true), -1)

		// The vote is recorded - the troll must not be able to tell - but it counts for nothing.
		assertEquals(-1, repository.myVote(root.id, troll.id))
		val reloaded = assertNotNull(repository.find(root.id))
		assertEquals(0, reloaded.down)
		assertEquals(0.0, reloaded.score)
	}

	@Test
	fun `a disliked comment sinks below a liked one`() {
		val workId = work()
		val liked = assertIs<PostResult.Posted>(post(workId, user("a"), text("liked"))).view.comment
		val disliked = assertIs<PostResult.Posted>(post(workId, user("b"), text("disliked"))).view.comment

		repeat(5) { service.vote(liked.id, user("v$it"), 1) }
		repeat(5) { service.vote(disliked.id, user("w$it"), -1) }

		val order = service.thread(workId, null, user("z"), sortByScore = true, limit = 25, offset = 0)
		assertEquals(listOf(liked.id, disliked.id), order.map { it.comment.id })
	}

	// -- editing and deleting --------------------------------------------------------------------

	@Test
	fun `an edit inside the window is applied`() {
		val workId = work()
		val author = user("a")
		val root = assertIs<PostResult.Posted>(post(workId, author)).view.comment

		val edited = service.edit(root.id, author, text("now with a typo fixed"), "en")
		assertEquals(text("now with a typo fixed"), assertIs<EditResult.Edited>(edited).view.comment.body)
	}

	@Test
	fun `an edit after the window is refused`() {
		val workId = work()
		val author = user("a")
		val root = assertIs<PostResult.Posted>(post(workId, author)).view.comment

		val later = CommentService(
			repository,
			clock = Clock.offset(
				Clock.system(ZoneOffset.UTC),
				Duration.ofMinutes(CommentRules.EDIT_WINDOW_MINUTES + 1),
			),
		)
		assertEquals(EditResult.NotEditable, later.edit(root.id, author, text("too late"), "en"))
	}

	@Test
	fun `editing someone else's comment is indistinguishable from it not existing`() {
		val workId = work()
		val root = assertIs<PostResult.Posted>(post(workId, user("a"))).view.comment
		assertEquals(EditResult.NotEditable, service.edit(root.id, user("b"), text("mine now"), "en"))
	}

	@Test
	fun `a deleted comment leaves a tombstone and its replies survive`() {
		val workId = work()
		val author = user("a")
		val root = assertIs<PostResult.Posted>(post(workId, author)).view.comment
		val reply = assertIs<PostResult.Posted>(post(workId, user("b"), text("reply"), root.id)).view.comment

		assertTrue(service.delete(root.id, author.id))

		val thread = service.thread(workId, null, user("c"), sortByScore = true, limit = 25, offset = 0)
		// The root stays as an empty tombstone, because dropping it would take the reply - somebody
		// else's words - down with it.
		val tombstone = assertNotNull(thread.firstOrNull { it.comment.id == root.id })
		assertEquals("", tombstone.comment.body)
		assertEquals("", tombstone.authorName)
		assertTrue(thread.any { it.comment.id == reply.id })
		assertEquals(CommentState.VISIBLE, assertNotNull(repository.find(reply.id)).state)
	}

	@Test
	fun `a ban clears the user's comments without tearing holes in threads`() {
		val workId = work()
		val banned = user("a")
		val other = user("b")
		val root = assertIs<PostResult.Posted>(post(workId, banned)).view.comment
		val reply = assertIs<PostResult.Posted>(post(workId, other, text("reply"), root.id)).view.comment

		assertEquals(1, service.purgeAllBy(banned.id))

		val tombstone = assertNotNull(repository.find(root.id))
		assertEquals(CommentState.REMOVED, tombstone.state)
		assertEquals("", tombstone.body)
		assertEquals(CommentState.VISIBLE, assertNotNull(repository.find(reply.id)).state)
	}

	// -- notifications ---------------------------------------------------------------------------

	@Test
	fun `a reply notifies the author once`() {
		val workId = work()
		val author = user("a")
		val replier = user("b")
		val root = assertIs<PostResult.Posted>(post(workId, author)).view.comment
		assertIs<PostResult.Posted>(post(workId, replier, text("reply"), root.id))

		val first = service.notifications(author.id, null, 25)
		assertEquals(1, first.size)

		// Second poll with the cursor from the first: nothing new.
		val cursor = first.first().comment.createdAt
		assertTrue(service.notifications(author.id, cursor, 25).isEmpty())
	}

	@Test
	fun `you are not notified of your own replies`() {
		val workId = work()
		val author = user("a")
		val root = assertIs<PostResult.Posted>(post(workId, author)).view.comment
		assertIs<PostResult.Posted>(post(workId, author, text("adding to my own"), root.id))

		assertTrue(service.notifications(author.id, null, 25).isEmpty())
	}

	@Test
	fun `a reply to a comment you deleted does not notify you`() {
		val workId = work()
		val author = user("a")
		val root = assertIs<PostResult.Posted>(post(workId, author)).view.comment
		assertIs<PostResult.Posted>(post(workId, user("b"), text("reply"), root.id))
		service.delete(root.id, author.id)

		assertTrue(service.notifications(author.id, null, 25).isEmpty())
	}

	@Test
	fun `a deleted comment with nothing under it is simply gone`() {
		val workId = work()
		val author = user("a")
		val root = assertIs<PostResult.Posted>(post(workId, author)).view.comment
		assertTrue(service.delete(root.id, author.id))

		val thread = service.thread(workId, null, user("c"), sortByScore = true, limit = 25, offset = 0)
		assertTrue(thread.isEmpty())
	}

	// -- languages -------------------------------------------------------------------------------

	@Test
	fun `languages are not unioned, but the other languages are counted`() {
		val workId = work()
		service.post(workId, null, user("en"), null, text("english"), false, "en")
		service.post(workId, null, user("es"), null, text("spanish"), false, "es-419")
		service.post(workId, null, user("es2"), null, text("spanish too"), false, "es")

		val english = service.thread(workId, null, user("z"), sortByScore = true, limit = 25, offset = 0, lang = "en")
		assertEquals(1, english.size)

		// The regional variant lands in the same bucket as the bare subtag.
		val spanish = service.thread(workId, null, user("z"), sortByScore = true, limit = 25, offset = 0, lang = "es")
		assertEquals(2, spanish.size)

		assertEquals(mapOf("en" to 1, "es" to 2), service.countsByLanguage(workId, null))
	}

	@Test
	fun `a reply in another language stays with its thread`() {
		val workId = work()
		val root = assertIs<PostResult.Posted>(
			service.post(workId, null, user("en"), null, text("english root"), false, "en"),
		).view.comment
		service.post(workId, null, user("fr"), root.id, text("reponse"), false, "fr")

		val english = service.thread(workId, null, user("z"), sortByScore = true, limit = 25, offset = 0, lang = "en")
		assertEquals(2, english.size)
	}

	// -- paging ----------------------------------------------------------------------------------

	@Test
	fun `paging is over roots so a thread never straddles a page boundary`() {
		val workId = work()
		val roots = (1..3).map {
			assertIs<PostResult.Posted>(post(workId, user("r$it"), text("root $it"))).view.comment
		}
		roots.forEach { root ->
			assertIs<PostResult.Posted>(post(workId, user("x${root.id}"), text("reply"), root.id))
		}

		val page = service.thread(workId, null, user("z"), sortByScore = false, limit = 1, offset = 0)
		assertEquals(1, page.count { it.comment.parentId == null })
		assertEquals(1, page.count { it.comment.parentId != null })
		assertFalse(page.isEmpty())
	}
}
