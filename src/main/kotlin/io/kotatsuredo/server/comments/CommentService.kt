package io.kotatsuredo.server.comments

import io.kotatsuredo.server.identity.Identity
import io.kotatsuredo.server.identity.Nicknames
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Everything a comment goes through between "the user pressed send" and "it is in the database".
 *
 * There is no hold queue and no approval step - comments publish instantly and moderators are the
 * backstop (PLAN.md §6) - so this is the only place the rules are applied, and every rule here is a
 * rule that cannot be undone after the fact.
 */
class CommentService(
	private val repository: CommentRepository,
	private val filter: ContentFilter = ContentFilter.PermitAll,
	private val detector: LanguageDetector = LanguageDetector.ClientHint,
	private val clock: Clock = Clock.systemUTC(),
) {

	fun post(
		workId: Long,
		chapterId: Long?,
		author: Identity,
		parentId: Long?,
		rawBody: String,
		isSpoiler: Boolean,
		lang: String?,
	): PostResult {
		val body = rawBody.trim()
		val length = CommentRules.length(body)
		if (length < CommentRules.MIN_BODY_LENGTH) return PostResult.TooShort(CommentRules.MIN_BODY_LENGTH)
		if (length > CommentRules.MAX_BODY_LENGTH) return PostResult.TooLong

		// Detected before filtering, because the detected language is what chooses the word list.
		val normalizedLang = detector.detect(body, normalizeLang(lang))
		val surface = if (parentId == null) FilterSurface.COMMENT else FilterSurface.REPLY
		var flaggedRule: String? = null
		when (val verdict = filter.check(body, normalizedLang, ContentFilter.Context(author.id, surface))) {
			is ContentFilter.Verdict.Blocked ->
				return PostResult.Blocked(verdict.term, verdict.tier, verdict.blockId)

			// Publishes exactly as normal. The flag is for the panel; the author is told nothing,
			// because telling them would make this a block with extra steps.
			is ContentFilter.Verdict.Flagged -> flaggedRule = verdict.term
			ContentFilter.Verdict.Allowed -> Unit
		}

		var depth = 0
		if (parentId != null) {
			val parent = repository.find(parentId) ?: return PostResult.ParentNotFound
			// A reply has to land on the thread it claims to be part of, and a removed parent has
			// nothing left to reply to.
			if (parent.workId != workId || parent.chapterId != chapterId) return PostResult.ParentNotFound
			if (parent.state == CommentState.REMOVED) return PostResult.ParentNotFound
			if (isChainExhausted(repository.ancestors(parentId), author.id)) {
				return PostResult.ChainDepthExceeded
			}
			depth = parent.depth + 1
		}

		// A shadowbanned user's comment is written exactly like anyone else's, and the response they
		// get back is indistinguishable from a normal one. That is the whole point.
		val state = if (author.isShadowbanned) CommentState.SHADOWED else CommentState.VISIBLE
		val id = repository.insert(
			workId = workId,
			chapterId = chapterId,
			userId = author.id,
			parentId = parentId,
			depth = depth,
			body = body,
			isSpoiler = isSpoiler,
			lang = normalizedLang,
			state = state,
			flaggedRule = flaggedRule,
		)
		val stored = requireNotNull(repository.find(id)) { "comment $id vanished after insert" }
		return PostResult.Posted(
			CommentView(stored, Nicknames.display(author.nickname, author.id), myVote = 0, isMine = true),
		)
	}

	/**
	 * Whether this author has already used up their rounds in the exchange directly above [chain].
	 *
	 * The rule caps a duel, not a discussion. Walking up from the parent, a third distinct voice ends
	 * the two-person segment: two people arguing is what this limits, three people talking is a
	 * conversation and is left alone (PLAN.md §6).
	 *
	 * @param chain ancestors nearest-first, starting at the parent.
	 */
	internal fun isChainExhausted(chain: List<Comment>, authorId: String): Boolean {
		val participants = mutableSetOf<String?>(authorId)
		var mine = 0
		for (comment in chain) {
			if (comment.userId !in participants && participants.size >= 2) break
			participants += comment.userId
			if (comment.userId == authorId) mine++
		}
		return participants.size == 2 && mine >= CommentRules.MAX_CHAIN_ROUNDS
	}

	/**
	 * Edits within the window.
	 *
	 * Every refusal returns the same [EditResult.NotEditable], deliberately: distinguishing "not
	 * yours" from "too late" would let anyone probe who wrote a shadowed comment.
	 */
	fun edit(commentId: Long, editor: Identity, rawBody: String, lang: String?): EditResult {
		val existing = repository.find(commentId) ?: return EditResult.NotFound
		if (existing.userId != editor.id) return EditResult.NotEditable
		if (existing.state == CommentState.REMOVED) return EditResult.NotEditable

		val age = Duration.between(existing.createdAt.toInstant(), clock.instant())
		if (age > Duration.ofMinutes(CommentRules.EDIT_WINDOW_MINUTES)) return EditResult.NotEditable

		val body = rawBody.trim()
		val length = CommentRules.length(body)
		if (length < CommentRules.MIN_BODY_LENGTH) return EditResult.TooShort(CommentRules.MIN_BODY_LENGTH)
		if (length > CommentRules.MAX_BODY_LENGTH) return EditResult.TooLong

		val normalizedLang = detector.detect(body, normalizeLang(lang)) ?: existing.lang
		val context = ContentFilter.Context(editor.id, FilterSurface.COMMENT)
		when (val verdict = filter.check(body, normalizedLang, context)) {
			is ContentFilter.Verdict.Blocked ->
				return EditResult.Blocked(verdict.term, verdict.tier, verdict.blockId)

			// An edit that trips `watch` is flagged the same way a new comment would be.
			is ContentFilter.Verdict.Flagged -> repository.flag(commentId, verdict.term)
			ContentFilter.Verdict.Allowed -> Unit
		}

		repository.update(commentId, body, normalizedLang)
		val updated = requireNotNull(repository.find(commentId))
		return EditResult.Edited(
			CommentView(
				comment = updated,
				authorName = Nicknames.display(editor.nickname, editor.id),
				myVote = repository.myVote(commentId, editor.id),
				isMine = true,
			),
		)
	}

	/** A user deleting their own comment. Leaves a tombstone so replies underneath survive. */
	fun delete(commentId: Long, userId: String): Boolean {
		val existing = repository.find(commentId) ?: return false
		if (existing.userId != userId) return false
		repository.setState(commentId, CommentState.REMOVED)
		return true
	}

	/**
	 * @param lang show only threads in this language. Languages are deliberately not unioned: a wall
	 *  of comments nobody in the room can read is worse than a short list they can. Replies are never
	 *  filtered - a reply belongs to its thread whatever language it is in.
	 */
	fun thread(
		workId: Long,
		chapterId: Long?,
		viewer: Identity,
		sortByScore: Boolean,
		limit: Int,
		offset: Int,
		lang: String? = null,
	): List<CommentView> {
		val roots = repository.listRoots(
			workId = workId,
			chapterId = chapterId,
			viewerId = viewer.id,
			lang = normalizeLang(lang),
			sortByScore = sortByScore,
			limit = limit.coerceIn(1, CommentRules.MAX_PAGE_SIZE),
			offset = offset.coerceAtLeast(0),
		)
		val all = roots + repository.descendantsOf(roots.map { it.id }, viewer.id)
		return decorate(all, viewer)
	}

	/**
	 * Visible comments per language, so the app can offer "also 12 in Spanish" rather than pretending
	 * the other languages do not exist.
	 */
	fun countsByLanguage(workId: Long, chapterId: Long?): Map<String, Int> =
		repository.countPerLanguage(workId, chapterId)

	fun view(commentId: Long, viewer: Identity): CommentView? {
		val comment = repository.find(commentId) ?: return null
		if (comment.state == CommentState.SHADOWED && comment.userId != viewer.id) return null
		return decorate(listOf(comment), viewer).firstOrNull()
	}

	fun count(workId: Long, chapterId: Long?): Int = repository.countForWork(workId, chapterId)

	/**
	 * @param value +1, -1, or 0 to withdraw.
	 * @return the comment as the voter now sees it, or null if there is nothing to vote on.
	 */
	fun vote(commentId: Long, voter: Identity, value: Int): CommentView? {
		require(value in -1..1) { "vote out of range: $value" }
		val comment = repository.find(commentId) ?: return null
		if (comment.state == CommentState.REMOVED) return null
		// Voting on your own comment is refused rather than ignored: with no report button, votes are
		// the moderation signal, and a self-upvote is noise in exactly the place it matters most.
		if (comment.userId == voter.id) return null
		// Shadowed comments are invisible to this voter, so they cannot be voting on one in good
		// faith - and answering at all would confirm it exists.
		if (comment.state == CommentState.SHADOWED) return null

		repository.setVote(commentId, voter.id, value)
		repository.recountVotes(commentId)
		return view(commentId, voter)
	}

	/**
	 * Replies to this user's comments since [since].
	 *
	 * Delivered once and never again: the cursor lives on the device, and the server keeps no
	 * "delivered" flag, because a table of who-was-told-what is exactly the kind of per-user history
	 * this design refuses to hold (PLAN.md §6).
	 */
	fun notifications(userId: String, since: OffsetDateTime?, limit: Int): List<ReplyNotification> {
		val floor = OffsetDateTime.now(clock).minusDays(CommentRules.NOTIFICATION_LOOKBACK_DAYS)
		val from = since?.takeIf { it.isAfter(floor) } ?: floor
		val replies = repository.notifications(userId, from, limit.coerceIn(1, CommentRules.MAX_PAGE_SIZE))
		// A reply whose author has since deleted their account still has to be announced - the
		// notification is about the reply, and the thread is where the conversation is.
		val names = repository.authorNames(replies.mapNotNull { it.userId }.toSet())
		return replies.map { reply ->
			val author = reply.userId
			ReplyNotification(
				comment = reply,
				authorName = if (author == null) "" else Nicknames.display(names[author], author),
			)
		}
	}

	/** A ban deletes the user's comments, as promised on the rules page (PLAN.md §6). */
	fun purgeAllBy(userId: String): Int = repository.tombstoneAllBy(userId)

	private fun decorate(comments: List<Comment>, viewer: Identity): List<CommentView> {
		val names = repository.authorNames(comments.mapNotNull { it.userId }.toSet())
		val votes = repository.myVotes(comments.map { it.id }, viewer.id)
		return comments.map { comment ->
			val author = comment.userId
			// A tombstone carries nothing, and neither does a comment whose author deleted their
			// account: in both cases the row exists only so the replies below it still make sense.
			val anonymous = comment.state == CommentState.REMOVED || author == null
			CommentView(
				comment = if (anonymous) comment.copy(body = "") else comment,
				authorName = if (anonymous || author == null) "" else Nicknames.display(names[author], author),
				myVote = votes[comment.id] ?: 0,
				isMine = author != null && author == viewer.id,
			)
		}
	}

	/** Keeps `lang` to a bare language subtag, so `pt-BR` and `pt_BR` land in the same bucket. */
	private fun normalizeLang(lang: String?): String? = lang
		?.trim()
		?.lowercase()
		?.takeWhile { it.isLetter() }
		?.takeIf { it.length in 2..3 }
}
