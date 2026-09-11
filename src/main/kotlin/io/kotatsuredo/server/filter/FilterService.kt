package io.kotatsuredo.server.filter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

private val log = LoggerFactory.getLogger("FilterService")

/**
 * Everything around the filter that is not matching: seeding the lists, tuning them from the block
 * log, and forgetting the text once the review window closes.
 */
class FilterService(
	private val repository: FilterRepository,
	private val filter: WordFilter,
	private val clock: Clock = Clock.systemUTC(),
) {

	/**
	 * Loads the starter lists on a database that has none.
	 *
	 * Idempotent, and never overwrites: the seed is a starting point and the database is the source
	 * of truth from the first boot onwards, so a moderator who disables a rule does not find it back
	 * after the next deploy.
	 */
	fun seed(): Int {
		var added = 0
		var skipped = 0
		SEED_FILES.forEach { (resource, spec) ->
			val stream = javaClass.getResourceAsStream("/filter/seed/$resource") ?: return@forEach
			stream.bufferedReader().useLines { lines ->
				lines.forEach { line ->
					val raw = line.trim()
					if (raw.isEmpty() || raw.startsWith('#')) return@forEach
					// Folded exactly as the matcher will fold the text, which for Vietnamese, Turkish
					// and Polish means keeping the marks: a rule stored without them would never
					// match the language it was written for.
					val normalized = when (spec.tier) {
						FilterTier.SEVERE -> FilterNormalizer.setB(raw)
						else -> FilterNormalizer.setA(raw, spec.lang)
					}
					// Checked on the folded form: `xxx` collapses to `x`, and a one-character rule in
					// a substring-matched tier blocks essentially every comment ever written.
					if (normalized.length < FilterRules.MIN_TERM_LENGTH) {
						skipped++
						return@forEach
					}
					if (repository.addRule(normalized, raw, spec.tier, spec.lang, "seed")) added++
				}
			}
		}
		if (added > 0) log.info("Seeded {} filter rules from the starter lists", added)
		if (skipped > 0) log.info("Skipped {} seed terms that normalise too short to be safe", skipped)
		return added
	}

	/** Rebuilds the automaton. Called after any change a moderator makes from the panel. */
	fun reload() = filter.reload()

	/**
	 * Turns off rules that keep being wrong.
	 *
	 * A rule whose confirmed false-positive rate crosses the threshold stops blocking on its own
	 * rather than waiting for someone to notice - which, on a list of tens of thousands of terms
	 * nobody has read end to end, is the difference between "strict" and "broken" (PLAN.md §6).
	 */
	fun demoteMisfiringRules(): List<Long> {
		val demoted = repository.ruleStats(FilterRules.AUTO_DEMOTE_MIN_BLOCKS)
			.filter { it.ruleId != null && it.falsePositiveRate >= FilterRules.AUTO_DEMOTE_RATE }
			.mapNotNull { stats ->
				stats.ruleId?.takeIf { repository.demoteRule(it) }?.also {
					log.info(
						"Auto-demoted filter rule {} ('{}', {}): {} of {} blocks were disputed",
						it, stats.term, stats.lang ?: "global", stats.disputed, stats.blocks,
					)
				}
			}
		if (demoted.isNotEmpty()) reload()
		return demoted
	}

	/** Forgets the blocked text. The row and its counts stay, because those are what tune the lists. */
	fun purgeOldContext(): Int {
		val before = OffsetDateTime.now(clock).minusDays(FilterRules.CONTEXT_RETENTION_DAYS)
		val purged = repository.purgeOldContext(before)
		if (purged > 0) log.info("Cleared the retained text of {} filter blocks", purged)
		return purged
	}

	/**
	 * Both maintenance jobs, on a loop.
	 *
	 * Retention is a promise in the privacy notice, so it runs for as long as the server does; if it
	 * stopped, the sentence about the review window would quietly stop being true.
	 */
	fun schedule(scope: CoroutineScope): Job = scope.launch {
		while (isActive) {
			runCatching {
				purgeOldContext()
				demoteMisfiringRules()
			}.onFailure { log.error("Filter maintenance pass failed", it) }
			delay(SWEEP_INTERVAL.toMillis())
		}
	}

	private data class Spec(val tier: FilterTier, val lang: String?)

	private companion object {
		val SWEEP_INTERVAL: Duration = Duration.ofHours(6)

		/**
		 * `severe.txt` is global; everything else is named `<lang>-<tier>.txt`.
		 *
		 * Listed explicitly rather than discovered by scanning, because a jar is not a directory and
		 * resource enumeration inside one is a well-known way to work everywhere except production.
		 */
		val SEED_FILES = listOf(
			"severe.txt" to Spec(FilterTier.SEVERE, null),
			"en-profanity.txt" to Spec(FilterTier.PROFANITY, "en"),
			"es-profanity.txt" to Spec(FilterTier.PROFANITY, "es"),
			"pt-profanity.txt" to Spec(FilterTier.PROFANITY, "pt"),
			"fr-profanity.txt" to Spec(FilterTier.PROFANITY, "fr"),
			"de-profanity.txt" to Spec(FilterTier.PROFANITY, "de"),
			"it-profanity.txt" to Spec(FilterTier.PROFANITY, "it"),
			"pl-profanity.txt" to Spec(FilterTier.PROFANITY, "pl"),
			"ru-profanity.txt" to Spec(FilterTier.PROFANITY, "ru"),
			"tr-profanity.txt" to Spec(FilterTier.PROFANITY, "tr"),
			"id-profanity.txt" to Spec(FilterTier.PROFANITY, "id"),
			"vi-profanity.txt" to Spec(FilterTier.PROFANITY, "vi"),
			"ja-profanity.txt" to Spec(FilterTier.PROFANITY, "ja"),
			"zh-profanity.txt" to Spec(FilterTier.PROFANITY, "zh"),
			"ko-profanity.txt" to Spec(FilterTier.PROFANITY, "ko"),
			"th-profanity.txt" to Spec(FilterTier.PROFANITY, "th"),
			"en-watch.txt" to Spec(FilterTier.WATCH, "en"),
			"es-watch.txt" to Spec(FilterTier.WATCH, "es"),
			"pt-watch.txt" to Spec(FilterTier.WATCH, "pt"),
			"fr-watch.txt" to Spec(FilterTier.WATCH, "fr"),
			"de-watch.txt" to Spec(FilterTier.WATCH, "de"),
			"it-watch.txt" to Spec(FilterTier.WATCH, "it"),
		)
	}
}
