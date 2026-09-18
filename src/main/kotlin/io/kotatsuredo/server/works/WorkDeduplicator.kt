package io.kotatsuredo.server.works

import io.kotatsuredo.server.ratings.RatingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("WorkDeduplicator")

/** One active work as the duplicate sweep sees it. */
data class DedupeWork(
	val id: Long,
	val canonicalTitle: String,
	val year: Int?,
	val contentType: String?,
	/** Titles at trusted weight (catalogue renderings), raw, so keys follow the current normalizer. */
	val trustedTitles: List<String> = emptyList(),
	/** provider -> external ids. */
	val externalIds: Map<String, Set<String>> = emptyMap(),
	/** Ratings, comments and account observations: which side of a merge people already use. */
	val activity: Int = 0,
)

data class PlannedMerge(val from: Long, val into: Long, val key: String)

/**
 * Finds works that are the same story split across sources.
 *
 * A split happens when a work is first seen under a rendering nothing indexed yet - "Past Life
 * Returner (Remake 2022)" before `remake` was an edition word, or while every catalogue was
 * unreachable. The resolver never merges existing works, so without this sweep the split is permanent
 * and each half collects its own ratings and comments.
 *
 * Deliberately narrower than the resolver: a work's *canonical* title has to hit another work's
 * canonical or catalogue title exactly, and every guard below has to pass. A missed duplicate is picked
 * up by a later sweep or a human; a wrong merge mixes two communities.
 */
object DuplicateFinder {

	/** A key shared by more works than this is a generic title, not a duplicate. */
	const val MAX_WORKS_PER_KEY = 8

	fun plan(works: List<DedupeWork>, blockedPairs: Set<Pair<Long, Long>> = emptySet()): List<PlannedMerge> {
		val byId = works.associateBy { it.id }
		val canonicalKeys = works.associate { work ->
			work.id to if (isTagOnly(work.canonicalTitle)) emptySet() else TitleNormalizer.keys(work.canonicalTitle)
		}

		val canonicalIndex = HashMap<String, MutableSet<Long>>()
		val trustedIndex = HashMap<String, MutableSet<Long>>()
		works.forEach { work ->
			canonicalKeys.getValue(work.id).forEach { canonicalIndex.getOrPut(it) { mutableSetOf() }.add(work.id) }
			work.trustedTitles.flatMap(TitleNormalizer::keys).forEach {
				trustedIndex.getOrPut(it) { mutableSetOf() }.add(work.id)
			}
		}

		val clusters = Clusters(byId, blockedPairs)
		works.sortedBy { it.id }.forEach { work ->
			canonicalKeys.getValue(work.id).forEach { key ->
				// Canonical against canonical: both sides call the work exactly this.
				canonicalIndex[key]?.takeIf { it.size in 2..MAX_WORKS_PER_KEY }?.forEach { other ->
					if (other != work.id && pairAllowed(work, byId.getValue(other))) clusters.union(work.id, other, key)
				}
			}

			// Canonical against a catalogue alternative ("Reincarnator" is an alt title of Past Life
			// Returner and the canonical title of a different manhwa). Only a work with no catalogue
			// identity of its own may join this way, and only when exactly one work claims the title.
			if (work.externalIds.isEmpty()) {
				val claimants = canonicalKeys.getValue(work.id)
					.flatMap { key -> trustedIndex[key].orEmpty().map { it to key } }
					.filter { (other, _) -> other != work.id }
					.distinctBy { it.first }
				val anchored = claimants.filter { (other, _) -> byId.getValue(other).externalIds.isNotEmpty() }
				val chosen = anchored.singleOrNull() ?: claimants.singleOrNull()
				if (chosen != null && pairAllowed(work, byId.getValue(chosen.first))) {
					clusters.union(work.id, chosen.first, chosen.second)
				}
			}
		}
		return clusters.plan()
	}

	/** "(ai generated)": a source put a tag where the title belongs, so the key names no work. */
	private fun isTagOnly(title: String): Boolean = TAG_ONLY.matches(title.trim())

	private val TAG_ONLY = Regex("""[(\[{][^)\]}]*[)\]}]""")
	private val TITLE_YEAR = Regex("""\b(1[89]\d{2}|20\d{2})\b""")

	/**
	 * The normalizer drops years from titles, which is right for "Chainsaw Man (2022)" but would make
	 * "Batman (1940)" and "Batman (2016-)" one work. When the year field is empty, the title's year counts.
	 */
	private val DedupeWork.effectiveYear: Int?
		// The first year is the start of a run: "The Ultimates (2002-2004)" is not "The Ultimates (2024-)".
		get() = year ?: TITLE_YEAR.find(canonicalTitle)?.value?.toInt()

	/**
	 * Whether an earlier automatic merge would still be made with today's guards. External ids are not
	 * compared: the merge moved them onto [into], so [from] no longer has any to disagree with.
	 */
	fun stillCompatible(from: DedupeWork, into: DedupeWork): Boolean =
		!isTagOnly(from.canonicalTitle) &&
			WorkCompatibility.isCompatible(
				from.canonicalTitle, from.effectiveYear, from.contentType,
				Triple(into.canonicalTitle, into.effectiveYear, into.contentType),
			)

	private fun pairAllowed(a: DedupeWork, b: DedupeWork): Boolean =
		WorkCompatibility.isCompatible(
			a.canonicalTitle, a.effectiveYear, a.contentType,
			Triple(b.canonicalTitle, b.effectiveYear, b.contentType),
		) && identitiesAgree(a, b)

	/**
	 * Two catalogue-backed works are only the same work when they share an id. Two catalogues that
	 * independently found *different* entries for one title is the "two works, same name" case, and
	 * Kitsu and MangaUpdates spell MangaUpdates ids differently, so a provider mismatch proves nothing.
	 */
	private fun identitiesAgree(a: DedupeWork, b: DedupeWork): Boolean {
		if (a.externalIds.isEmpty() || b.externalIds.isEmpty()) return true
		val conflict = a.externalIds.any { (provider, ids) -> b.externalIds[provider]?.let { it != ids } == true }
		val shared = a.externalIds.any { (provider, ids) -> b.externalIds[provider].orEmpty().any(ids::contains) }
		return shared && !conflict
	}

	/** Union-find that refuses any join which would put two incompatible works in one group. */
	private class Clusters(
		private val works: Map<Long, DedupeWork>,
		private val blocked: Set<Pair<Long, Long>>,
	) {
		private val parent = HashMap<Long, Long>()
		private val members = HashMap<Long, MutableList<Long>>()
		private val keys = HashMap<Long, String>()

		private fun find(id: Long): Long {
			var root = id
			while (parent[root]?.let { it != root } == true) root = parent.getValue(root)
			parent[id] = root
			return root
		}

		fun union(a: Long, b: Long, key: String) {
			val rootA = find(a)
			val rootB = find(b)
			if (rootA == rootB) return
			val groupA = members[rootA] ?: listOf(rootA)
			val groupB = members[rootB] ?: listOf(rootB)
			if (!joinable(groupA, groupB)) return
			parent[rootB] = rootA
			parent.putIfAbsent(rootA, rootA)
			members[rootA] = (groupA + groupB).toMutableList()
			members.remove(rootB)
			keys.putIfAbsent(rootA, keys[rootB] ?: key)
		}

		private fun joinable(groupA: List<Long>, groupB: List<Long>): Boolean {
			val all = (groupA + groupB).map(works::getValue)
			// A pair a moderator unmerged, or linked as sequel/prequel, never meets again transitively.
			if (groupA.any { a -> groupB.any { b -> (a to b) in blocked || (b to a) in blocked } }) return false
			val years = all.mapNotNull { it.effectiveYear }
			if (years.isNotEmpty() && years.max() - years.min() > WorkCompatibility.YEAR_TOLERANCE) return false
			val types = all.mapNotNull { it.contentType }.distinct()
			if (types.any { a -> types.any { b -> !WorkCompatibility.compatibleContentTypes(a, b) } }) return false
			val signatures = all.map { TitleNormalizer.sequenceSignature(it.canonicalTitle) }.distinct()
			if (signatures.size > 1) return false
			val anchored = all.filter { it.externalIds.isNotEmpty() }
			return anchored.all { a -> anchored.all { b -> a === b || identitiesAgree(a, b) } }
		}

		fun plan(): List<PlannedMerge> = members.flatMap { (root, group) ->
			// The survivor is the catalogue-backed work, then the one people already use, then the oldest.
			val into = group.map(works::getValue).sortedWith(
				compareByDescending<DedupeWork> { it.externalIds.isNotEmpty() }
					.thenByDescending { it.activity }
					.thenBy { it.id },
			).first().id
			group.filter { it != into }.sorted().map { PlannedMerge(from = it, into = into, key = keys.getValue(root)) }
		}
	}
}

/**
 * Periodically merges duplicate works (see [DuplicateFinder]).
 *
 * Every merge goes through [WorkRepository.mergeWorks], so it is logged with reason [REASON] and can
 * be undone from the admin panel; an undone pair is never merged again.
 */
class WorkDeduplicator(
	private val repository: WorkRepository,
	private val ratings: RatingService,
	private val maxMergesPerRun: Int = MAX_MERGES_PER_RUN,
) {

	data class Report(val undone: Int, val reindexedTitles: Int, val planned: Int, val merged: Int)

	fun run(dryRun: Boolean = false): Report {
		// A guard added after a merge was made applies to that merge too. Undone pairs are then blocked,
		// so the planner below cannot make the same mistake again.
		var undone = 0
		repository.activeAutoMerges(REASON)
			.filterNot { (from, into) -> DuplicateFinder.stillCompatible(from, into) }
			.forEach { (from, into) ->
				if (dryRun) {
					log.info("Would undo merge of work {} into {}", from.id, into.id)
					return@forEach
				}
				runCatching {
					repository.unmergeWork(from.id)?.let { (restored, target) ->
						ratings.recompute(restored)
						ratings.recompute(target)
					}
				}.onSuccess {
					undone++
					log.info("Undid merge of work {} [{}] into {} [{}]", from.id, from.canonicalTitle, into.id, into.canonicalTitle)
				}.onFailure { log.warn("Failed to undo merge of work {} into {}", from.id, into.id, it) }
			}

		// Keys stored before a normalizer change stay stale until reindexed; new keys only ever add rows.
		val reindexed = if (dryRun) 0 else repository.reindexTitleKeys()
		val plan = DuplicateFinder.plan(repository.dedupeSnapshot(), repository.blockedMergePairs())
		if (plan.size > maxMergesPerRun) {
			log.warn("Duplicate sweep planned {} merges; applying the first {}", plan.size, maxMergesPerRun)
		}
		var merged = 0
		plan.take(maxMergesPerRun).forEach { merge ->
			if (dryRun) {
				log.info("Would merge work {} into {} (key '{}')", merge.from, merge.into, merge.key)
				return@forEach
			}
			runCatching {
				repository.mergeWorks(from = merge.from, into = merge.into, reason = REASON)
				ratings.recompute(merge.into)
				ratings.recompute(merge.from)
			}.onSuccess {
				merged++
				log.info("Merged duplicate work {} into {} (key '{}')", merge.from, merge.into, merge.key)
			}.onFailure { log.warn("Failed to merge work {} into {}", merge.from, merge.into, it) }
		}
		return Report(undone, reindexed, plan.size, merged)
	}

	fun schedule(
		scope: CoroutineScope,
		initialDelay: Duration = 5.minutes,
		interval: Duration = 6.hours,
	) = scope.launch(Dispatchers.IO) {
		delay(initialDelay)
		while (isActive) {
			runCatching { run() }
				.onSuccess { log.info("Duplicate sweep: {}", it) }
				.onFailure { log.warn("Duplicate sweep failed", it) }
			delay(interval)
		}
	}

	companion object {
		const val REASON = "auto_dedupe"
		/** Bounds the damage of a bad normalizer change: the rest waits for the next sweep. */
		const val MAX_MERGES_PER_RUN = 500
	}
}
