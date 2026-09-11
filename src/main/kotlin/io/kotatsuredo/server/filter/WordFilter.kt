package io.kotatsuredo.server.filter

import io.kotatsuredo.server.comments.ContentFilter
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("WordFilter")

/**
 * A match, with everything the block log needs to be tunable later.
 */
data class FilterHit(
	val term: String,
	val tier: FilterTier,
	val ruleId: Long?,
	val lang: String?,
)

/**
 * The word filter.
 *
 * Synchronous, in the POST handler, with no human in the critical path: pass and the comment is
 * published immediately, fail and it is rejected naming the offending term (PLAN.md §6).
 *
 * The whole design rests on one rule doing most of the work — block only if the match is the whole
 * token, or the containing token is not itself a known word. That is what makes `assassin` and
 * `Scunthorpe` postable without any context model, and it is what makes set B's substring matching
 * safe enough to use at all.
 */
class WordFilter(
	private val repository: FilterRepository,
	private val languages: Collection<String> = LAUNCH_LANGUAGES,
) : ContentFilter {

	/**
	 * Everything matching needs, swapped atomically.
	 *
	 * Rebuilt rather than mutated so a list update never leaves a half-populated automaton visible to
	 * a request in flight, and so the lists can be retuned from the panel without a restart.
	 */
	private class Snapshot(
		val severe: AhoCorasick,
		val severeRules: Map<String, Long>,
		val perLanguage: Map<String, LanguageLists>,
		val dictionary: WordDictionary,
		/** A moderator's explicit call. Exempt from every tier, including severe. */
		val manualAllow: Set<String>,
		/** Work titles and bundled words. Exempt from profanity and watch, never from severe. */
		val softAllow: Set<String>,
	) {
		companion object {
			val EMPTY = Snapshot(
				severe = AhoCorasick.build(emptyList()),
				severeRules = emptyMap(),
				perLanguage = emptyMap(),
				dictionary = WordDictionary.Empty,
				manualAllow = emptySet(),
				softAllow = emptySet(),
			)
		}
	}

	/**
	 * Both forms of each list.
	 *
	 * The set answers "is this token exactly a term" and the automaton answers "does this token
	 * contain one". They are not interchangeable: the automaton stops at the first match it
	 * completes, so asking it the first question lets a short rule mask a longer one - `sik` finishes
	 * three characters into `siktir` and the whole-token rule never gets asked.
	 */
	private class LanguageLists(
		val profanity: AhoCorasick,
		val profanityTerms: Set<String>,
		val profanityRules: Map<String, Long>,
		val watch: AhoCorasick,
		val watchTerms: Set<String>,
		val watchRules: Map<String, Long>,
	)

	@Volatile
	private var snapshot: Snapshot = Snapshot.EMPTY

	/**
	 * Rebuilds from the database.
	 *
	 * Called at startup and whenever the panel changes a rule, because a moderator who allowlists
	 * `assassin` expects the next comment to go through, not the next deploy.
	 */
	fun reload() {
		val rules = repository.activeRules()
		val allowRows = repository.allowedTerms()

		val severeTerms = rules.filter { it.tier == FilterTier.SEVERE }
		val severe = AhoCorasick.build(severeTerms.map { it.term })
		val severeRules = severeTerms.associate { it.term to it.id }

		val perLanguage = rules
			.filter { it.tier != FilterTier.SEVERE }
			.groupBy { it.lang ?: GLOBAL }
			.mapValues { (_, forLanguage) ->
				val profanity = forLanguage.filter { it.tier == FilterTier.PROFANITY }
				val watch = forLanguage.filter { it.tier == FilterTier.WATCH }
				LanguageLists(
					profanity = AhoCorasick.build(profanity.map { it.term }),
					profanityTerms = profanity.mapTo(mutableSetOf()) { it.term },
					profanityRules = profanity.associate { it.term to it.id },
					watch = AhoCorasick.build(watch.map { it.term }),
					watchTerms = watch.mapTo(mutableSetOf()) { it.term },
					watchRules = watch.associate { it.term to it.id },
				)
			}

		val catalogue = runCatching { repository.catalogueTitleTokens(MAX_CATALOGUE_TITLES) }
			.onFailure { log.warn("Could not load catalogue title tokens for the allowlist", it) }
			.getOrDefault(emptySet())
		val bundled = ResourceDictionary.load(languages)

		snapshot = Snapshot(
			severe = severe,
			severeRules = severeRules,
			perLanguage = perLanguage,
			dictionary = CompositeDictionary(listOf(bundled, WordDictionary.of(catalogue))),
			manualAllow = allowRows,
			softAllow = catalogue,
		)
		log.info(
			"Filter loaded: {} rules across {} languages, {} dictionary words, {} catalogue tokens",
			rules.size, perLanguage.size, bundled.size, catalogue.size,
		)
	}

	override fun check(body: String, lang: String?, context: ContentFilter.Context): ContentFilter.Verdict {
		val hit = match(body, lang) ?: return ContentFilter.Verdict.Allowed
		if (!hit.tier.blocks) return ContentFilter.Verdict.Flagged(hit.term, hit.tier.name.lowercase())

		// Logged here rather than by the caller, because every caller would have to remember and the
		// one that forgets is the one whose language quietly stops working.
		val blockId = runCatching {
			repository.recordBlock(
				ruleId = hit.ruleId,
				term = hit.term,
				tier = hit.tier,
				lang = hit.lang,
				surface = context.surface,
				userId = context.userId,
				context = body,
			)
		}.onFailure { log.warn("Could not log a filter block", it) }.getOrNull()

		return ContentFilter.Verdict.Blocked(hit.term, hit.tier.name.lowercase(), blockId)
	}

	/**
	 * The full result, for callers that need to log the block.
	 *
	 * Severe first: it is global and it is the tier where a miss is harmful, so it must not be
	 * shadowed by a profanity match that happens to appear earlier in the text.
	 */
	fun match(body: String, lang: String?): FilterHit? {
		val current = snapshot
		val tokens = FilterNormalizer.tokenize(body)
		if (tokens.isEmpty()) return null

		severeHit(current, tokens, body, lang)?.let { return it }

		// Profanity and watch are scoped to the comment's own language plus English. Never a union of
		// all fifteen: a word innocuous in one language is profane in another, and unioning them
		// would block a great deal of ordinary text.
		// The containing-word check can only vouch for a token in a language it has a dictionary for.
		// Without one, a proper-substring match has nothing to rescue it: the English rule `ass` finds
		// `passado`, the Portuguese for "past", and blocks an ordinary sentence. So outside the
		// languages with a dictionary, profanity is matched as a whole token only - which still
		// catches someone writing `shit` in a Portuguese comment, and stops inventing false positives
		// in a language nobody here can spell-check.
		val substringSafe = lang != null && lang in DICTIONARY_LANGUAGES

		val scoped = listOfNotNull(lang, ENGLISH).distinct()
		for (language in scoped) {
			val lists = current.perLanguage[language] ?: continue
			tokenHit(
				current, tokens, lists.profanity, lists.profanityTerms, lists.profanityRules,
				FilterTier.PROFANITY, language, substringSafe,
			)?.let { return it }
		}
		lang?.let { language ->
			current.perLanguage[language]?.let { lists ->
				tokenHit(
					current, tokens, lists.watch, lists.watchTerms, lists.watchRules,
					FilterTier.WATCH, language, substringSafe,
				)?.let { return it }
			}
		}
		return null
	}

	private fun severeHit(
		current: Snapshot,
		tokens: List<String>,
		body: String,
		lang: String?,
	): FilterHit? {
		if (current.severe.isEmpty) return null

		for (token in tokens) {
			val folded = FilterNormalizer.setB(token)
			if (folded.isEmpty()) continue
			if (folded in current.manualAllow) continue

			if (folded in current.severeRules) {
				return FilterHit(folded, FilterTier.SEVERE, current.severeRules[folded], lang)
			}
			// A proper substring only counts for a term long enough that accidental containment is
			// implausible. `жид` is a genuine slur and three characters, and it sits inside `жидкость`
			// - the ordinary Russian for "liquid". Whole-token matching still blocks it standing
			// alone, which is how it is actually used.
			val match = current.severe.findFirst(folded) ?: continue
			if (match.length < MIN_SEVERE_SUBSTRING_LENGTH) continue
			if (blocksToken(current, folded, match.length)) {
				return FilterHit(match.term, FilterTier.SEVERE, current.severeRules[match.term], lang)
			}
		}

		// Separator evasion: `f u c k` is four tokens and one word. Windows are bounded to a few short
		// tokens, which keeps this trivial and, more usefully, keeps `the rap ist` in scope so the
		// dictionary can rescue `therapist`.
		glueWindows(tokens).forEach { window ->
			val folded = FilterNormalizer.setB(window)
			if (folded in current.manualAllow) return@forEach
			if (folded in current.severeRules) {
				return FilterHit(folded, FilterTier.SEVERE, current.severeRules[folded], lang)
			}
		}

		// Japanese, Chinese, Korean and Thai write without spaces, so "the whole token" is undefined
		// and this defence is simply unavailable. Substring matching over the whole text, with a
		// minimum term length, is what is left - accept the higher false-positive rate and watch
		// those four rejection rates specifically.
		if (FilterNormalizer.isBoundaryless(lang)) {
			val folded = FilterNormalizer.setB(body)
			current.severe.findAll(folded)
				.firstOrNull { it.length >= FilterNormalizer.MIN_SUBSTRING_LENGTH }
				?.let { return FilterHit(it.term, FilterTier.SEVERE, current.severeRules[it.term], lang) }
		}
		return null
	}

	private fun tokenHit(
		current: Snapshot,
		tokens: List<String>,
		automaton: AhoCorasick,
		terms: Set<String>,
		ruleIds: Map<String, Long>,
		tier: FilterTier,
		lang: String,
		substringSafe: Boolean,
	): FilterHit? {
		if (terms.isEmpty()) return null
		for (token in tokens) {
			val folded = FilterNormalizer.setA(token, lang)
			if (folded.isEmpty()) continue
			// The catalogue exemption applies here and not to severe: people discuss works with
			// deliberately crude titles, and romanised Japanese collides with profanity in several
			// European languages.
			if (folded in current.manualAllow || folded in current.softAllow) continue

			if (folded in terms) return FilterHit(folded, tier, ruleIds[folded], lang)
			if (!substringSafe) continue
			val match = automaton.findFirst(folded) ?: continue
			if (blocksToken(current, folded, match.length)) {
				return FilterHit(match.term, tier, ruleIds[match.term], lang)
			}
		}

		// Separator evasion applies here as well as to severe. The plan confines *global* separator
		// stripping to set B, and that stays true - this is a bounded window over a few short tokens,
		// not global stripping. Without it `f u c k` walks straight through a filter whose whole
		// stated posture is strict.
		for (window in glueWindows(tokens)) {
			val folded = FilterNormalizer.setA(window, lang)
			if (folded in current.manualAllow || folded in current.softAllow) continue
			if (folded in terms) return FilterHit(folded, tier, ruleIds[folded], lang)
		}
		return null
	}

	/**
	 * The containing-word check.
	 *
	 * A whole-token match always blocks - the dictionary never gets a say there, because spellcheck
	 * dictionaries contain profanity too and consulting it would allow everything. A proper substring
	 * blocks only when the token around it is not a word anyone recognises.
	 */
	private fun blocksToken(current: Snapshot, token: String, matchLength: Int): Boolean =
		matchLength == token.length || !current.dictionary.contains(token)

	/*
	 * A glued window is looked up as a whole term rather than scanned for substrings, which is what
	 * makes gluing safe. The containing-word check is not enough on its own here: `an ass` glues to
	 * `anass`, which is not a dictionary word, so the dictionary would have blocked an ordinary
	 * sentence. Separator evasion has a much narrower signature - `f u c k` reassembles into exactly
	 * the term and nothing more - so an exact lookup removes the whole class of accident, `the rap
	 * ist` included.
	 */

	private fun glueWindows(tokens: List<String>): List<String> {
		val windows = mutableListOf<String>()
		for (start in tokens.indices) {
			if (tokens[start].length > FilterRules.MAX_GLUE_TOKEN_LENGTH) continue
			val builder = StringBuilder(tokens[start])
			var count = 1
			var index = start + 1
			while (index < tokens.size && count < FilterRules.MAX_GLUE_TOKENS) {
				if (tokens[index].length > FilterRules.MAX_GLUE_TOKEN_LENGTH) break
				builder.append(tokens[index])
				count++
				index++
				if (count >= 2) windows.add(builder.toString())
			}
		}
		return windows
	}

	companion object {
		const val GLOBAL = "*"
		const val ENGLISH = "en"

		/**
		 * Languages this server can spell-check.
		 *
		 * Only these get proper-substring matching on `profanity`; everywhere else it is whole-token
		 * only. Adding `filter/words/<lang>.txt` and the language here is what turns substring
		 * matching on for it - and doing one without the other is how `passado` gets blocked.
		 */
		val DICTIONARY_LANGUAGES = setOf(ENGLISH)

		/**
		 * How long a `severe` term must be before it is matched as a proper substring.
		 *
		 * Short slurs are real - `жид` is three characters - but a three-character substring rule
		 * applied globally finds them inside ordinary words in languages this server cannot
		 * spell-check. Below this they are matched as whole tokens, which is how they are used.
		 */
		const val MIN_SEVERE_SUBSTRING_LENGTH = 5

		/** Dictionary-check works, plus the four boundaryless scripts (PLAN.md §6). */
		val LAUNCH_LANGUAGES = listOf(
			"en", "es", "pt", "fr", "de", "it", "pl", "ru", "tr", "id", "vi",
			"ja", "zh", "ko", "th",
		)

		/**
		 * A bound rather than a target. The catalogue is filled on demand, so this is only ever
		 * reached on an instance that has been running for a long time.
		 */
		const val MAX_CATALOGUE_TITLES = 200_000
	}
}
