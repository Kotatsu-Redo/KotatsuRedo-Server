package io.kotatsuredo.server.works

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WorkLinker")

sealed interface LinkOutcome {
	/** Both sources already pointed at this work; nothing to do. */
	data class AlreadyLinked(val workId: Long) : LinkOutcome

	data class Linked(val workId: Long) : LinkOutcome

	/** Two works merged into one. Reversible via `work_merge_log`. */
	data class Merged(val into: Long, val from: Long) : LinkOutcome

	/**
	 * Refused because both works already carry user content. Queued for a human instead - an
	 * unmerged pair is an annoyance, a wrong merge mixes two communities irreversibly (§2.7).
	 */
	data class NeedsReview(val a: Long, val b: Long) : LinkOutcome

	data object Unknown : LinkOutcome
}

/**
 * Applies a human's assertion that two source entries are the same work.
 *
 * The signal comes free from the app: when a user migrates a manga from one source to another, they
 * have just confirmed the two are the same thing far more reliably than any heuristic could (§2.6).
 * It is also the only path in the system that merges existing works, which is why the guard lives
 * here rather than in the resolver.
 */
/**
 * Where a pair too risky to merge automatically goes.
 *
 * An interface rather than a direct call into the moderation package, so the resolver keeps knowing
 * nothing about moderation - and so a deployment without a panel still resolves works.
 */
fun interface LinkReviewSink {
	fun needsReview(a: Long, b: Long, evidence: String)

	companion object {
		val Discard = LinkReviewSink { _, _, _ -> }
	}
}

class WorkLinker(
	private val repository: WorkRepository,
	private val review: LinkReviewSink = LinkReviewSink.Discard,
) {

	fun link(
		a: Pair<String, String>,
		b: Pair<String, String>,
		evidence: String,
	): LinkOutcome {
		val workA = repository.findByAlias(a.first, a.second)
		val workB = repository.findByAlias(b.first, b.second)

		return when {
			workA != null && workB != null && workA == workB -> LinkOutcome.AlreadyLinked(workA)

			// One side is known: point the other at it. No merge, so no guard needed.
			workA != null && workB == null -> {
				repository.linkAlias(b.first, b.second, workA, confidence = 1.0, evidence = evidence)
				LinkOutcome.Linked(workA)
			}

			workB != null && workA == null -> {
				repository.linkAlias(a.first, a.second, workB, confidence = 1.0, evidence = evidence)
				LinkOutcome.Linked(workB)
			}

			workA != null && workB != null -> merge(workA, workB, evidence)

			else -> LinkOutcome.Unknown
		}
	}

	/**
	 * Merges the work with less user content into the one with more, so the smaller side is what
	 * moves. Refuses outright when both sides carry content.
	 */
	private fun merge(workA: Long, workB: Long, evidence: String): LinkOutcome {
		val aHasContent = repository.hasUserContent(workA)
		val bHasContent = repository.hasUserContent(workB)

		if (aHasContent && bHasContent) {
			log.info("Refusing automatic merge of {} and {}: both carry user content", workA, workB)
			// Filed rather than dropped. Without this the refusal is invisible - the two communities
			// stay split and nobody ever finds out there was a decision to make (§2.7).
			review.needsReview(workA, workB, evidence)
			return LinkOutcome.NeedsReview(workA, workB)
		}

		val (into, from) = if (bHasContent) workB to workA else workA to workB
		repository.mergeWorks(from = from, into = into, reason = evidence)
		log.info("Merged work {} into {} ({})", from, into, evidence)
		return LinkOutcome.Merged(into = into, from = from)
	}
}
