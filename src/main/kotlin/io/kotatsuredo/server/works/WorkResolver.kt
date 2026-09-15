package io.kotatsuredo.server.works

import io.kotatsuredo.server.catalogue.CatalogueLookup
import io.kotatsuredo.server.catalogue.CatalogueLookupOverloaded
import io.kotatsuredo.server.catalogue.CatalogueRecord
import org.slf4j.LoggerFactory
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit

private val log = LoggerFactory.getLogger("WorkResolver")

/**
 * Turns "this source calls it X" into "this is work N".
 *
 * The ladder runs cheapest-first and stops at the first confident answer. Ordering is not an
 * optimisation detail - it is the precision strategy. Every rung above the fuzzy one is exact, so the
 * fuzzy rung only ever sees what nothing else could explain.
 *
 * **Resolving never merges two existing works.** It assigns an alias to one work, or creates one.
 * Merging is a separate, guarded operation (see [WorkLinker]) because a wrong merge is the failure
 * §2.7 refuses to accept and a wrong alias is trivially re-pointed.
 */
class WorkResolver(
	private val repository: WorkRepository,
	private val catalogue: CatalogueLookup?,
	private val candidateThreshold: Double = CANDIDATE_THRESHOLD,
	maxConcurrent: Int = MAX_CONCURRENT_RESOLUTIONS,
	maxQueued: Int = MAX_QUEUED_RESOLUTIONS,
) {

	private val gate = Semaphore(maxConcurrent)
	private val admission = Semaphore(maxConcurrent + maxQueued)
	private val aliasLocks = Array(ALIAS_LOCK_STRIPES) { Mutex() }
	private val aliasAdmission = Array(ALIAS_LOCK_STRIPES) {
		Semaphore(MAX_QUEUED_PER_ALIAS_STRIPE + 1)
	}

	/**
	 * Bounds JDBC work globally and coalesces concurrent creation attempts for the same source key.
	 * External catalogue waits have their own capacity pool and never retain a JDBC admission slot.
	 */
	suspend fun resolve(fingerprint: WorkFingerprint, reporterId: String? = null): Resolution {
		val lockIndex = Math.floorMod(31 * fingerprint.source.hashCode() + fingerprint.sourceKey.hashCode(), aliasLocks.size)
		return try {
			withAliasCapacity(lockIndex) {
				withResolutionCapacity { resolveLocal(fingerprint, reporterId) }
			} ?: run {
				// The provider wait holds neither JDBC nor an alias-stripe permit. Recheck locally after
				// reacquiring the lock because another source may have created the same work meanwhile.
				val record = catalogue?.lookup(fingerprint.title, fingerprint.year)
				withAliasCapacity(lockIndex) {
					withResolutionCapacity {
						resolveLocal(fingerprint, reporterId) ?: if (record == null) {
							createFromFingerprint(fingerprint, reporterId)
						} else {
							createFromCatalogue(fingerprint, record, reporterId)
						}
					}
				}
			}
		} catch (_: CatalogueLookupOverloaded) {
			throw WorkResolutionOverloaded()
		}
	}

	private suspend fun <T> withAliasCapacity(index: Int, block: suspend () -> T): T {
		val queue = aliasAdmission[index]
		if (!queue.tryAcquire()) throw WorkResolutionOverloaded()
		val mutex = aliasLocks[index]
		return try {
			mutex.lock()
			try {
				block()
			} finally {
				mutex.unlock()
			}
		} finally {
			queue.release()
		}
	}

	private suspend fun <T> withResolutionCapacity(block: () -> T): T {
		if (!admission.tryAcquire()) throw WorkResolutionOverloaded()
		return try {
			gate.withPermit { block() }
		} finally {
			admission.release()
		}
	}

	/** The local rungs of the ladder. A null result is the only point that may call a catalogue. */
	private fun resolveLocal(fingerprint: WorkFingerprint, reporterId: String?): Resolution? {
		// Alternative titles are useful metadata, but are also client-controlled. Only the primary
		// rendering may select or corroborate a cross-user mapping.
		val keys = (if (reporterId == null) fingerprint.allTitles else listOf(fingerprint.title))
			.flatMap(TitleNormalizer::keys)
			.toSet()
		// 1. Alias. O(1) and the overwhelming majority of traffic once a source is warm.
		val alias = if (reporterId == null) {
			repository.findByAlias(fingerprint.source, fingerprint.sourceKey)
		} else {
			repository.findVerifiedAlias(fingerprint.source, fingerprint.sourceKey)
		}
		alias?.let {
			return Resolution(it, ResolutionMethod.ALIAS, created = false)
		}

		if (reporterId != null) {
			repository.observedAlias(fingerprint.source, fingerprint.sourceKey, reporterId, keys)?.let { observed ->
				if (isCompatible(fingerprint, repository.metadataOf(observed))) {
					return Resolution(observed, ResolutionMethod.OBSERVATION, created = false)
				}
			}
			val pending = repository.matchingObservedAliases(fingerprint.source, fingerprint.sourceKey, keys)
			val pendingMetadata = repository.metadataOf(pending)
			pending.firstOrNull { isCompatible(fingerprint, pendingMetadata[it]) }?.let {
				repository.observeAlias(
					fingerprint.source, fingerprint.sourceKey, reporterId, it, keys,
				)
				return Resolution(it, ResolutionMethod.OBSERVATION, created = false)
			}
		}

		// Client-supplied external ids are hints, not authoritative catalogue data. Accepting them as
		// anchors lets one account re-point a global identifier and poison every later resolution.

		// 3. Exact normalized key. This is where a catalogue's renderings pay off: a source using a
		//    different spelling is usually not a matching problem at all, because the work is already
		//    indexed under that spelling.
		val minimumTitleWeight = if (reporterId == null) 0.0 else MIN_TRUSTED_TITLE_WEIGHT
		val exactCandidates = repository.findByTitleKeys(keys, minimumTitleWeight)
		val exactMetadata = repository.metadataOf(exactCandidates)
		exactCandidates
			.firstOrNull { candidate -> isCompatible(fingerprint, exactMetadata[candidate]) }
			?.let { return link(fingerprint, it, ResolutionMethod.EXACT_TITLE, reporterId) }

		// 4. Fuzzy, corroborated. Trigram narrows to a handful of candidates; the cover hash decides.
		//    The cover is never searched globally - only compared against candidates the title already
		//    produced, which is why no hash index is needed (PLAN.md §2.4).
		val candidatesByScore = repository.findSimilarTitles(
			keys, candidateThreshold, minimumWeight = minimumTitleWeight,
		).toMap()
		val fuzzyMetadata = repository.metadataOf(candidatesByScore.keys)
		val candidates = candidatesByScore
			.filterKeys { candidate -> isCompatible(fingerprint, fuzzyMetadata[candidate]) }

		if (candidates.isNotEmpty()) {
			verifyByCover(fingerprint, candidates.keys)?.let {
				return link(fingerprint, it, ResolutionMethod.TITLE_AND_COVER, reporterId)
			}
			val best = candidates.maxByOrNull { it.value }
			if (best != null && best.value >= TITLE_ONLY_THRESHOLD) {
				// Linked but flagged: a title that merely looks similar, with nothing backing it up.
				return link(fingerprint, best.key, ResolutionMethod.FUZZY_TITLE, reporterId)
			}
		}

		// Nothing local knows it. The caller releases JDBC capacity before asking the catalogue.
		return null
	}

	/**
	 * Rejects a candidate that a title match would otherwise accept.
	 *
	 * The sequence-marker check is the important half: "Tower of God" and "Tower of God Part 2"
	 * normalize differently, but a fuzzy match would happily pair them, and merging a sequel into its
	 * prequel leaks spoilers into a thread where nobody has read that far.
	 */
	private fun isCompatible(
		fingerprint: WorkFingerprint,
		metadata: Triple<String, Int?, String?>?,
	): Boolean {
		metadata ?: return false
		val (canonicalTitle, year, contentType) = metadata

		if (TitleNormalizer.sequenceSignature(fingerprint.title) !=
			TitleNormalizer.sequenceSignature(canonicalTitle)
		) {
			return false
		}

		// Years disagree between sources often enough that agreement cannot be required - but a gap
		// this wide means two different works with a shared title, which does happen.
		if (fingerprint.year != null && year != null && kotlin.math.abs(fingerprint.year - year) > YEAR_TOLERANCE) {
			return false
		}

		if (fingerprint.contentType != null && contentType != null &&
			!contentType.equals(fingerprint.contentType, ignoreCase = true) &&
			contentType.lowercase() in KNOWN_TYPES && fingerprint.contentType.lowercase() in KNOWN_TYPES
		) {
			return false
		}
		return true
	}

	/** @return the candidate whose cover matches, if any. */
	private fun verifyByCover(fingerprint: WorkFingerprint, candidates: Set<Long>): Long? {
		val phash = fingerprint.coverPHash ?: return null
		val hashes = repository.coverHashesFor(candidates)
		return candidates.firstOrNull { candidate ->
			hashes[candidate]?.any { known -> hammingDistance(known, phash) <= MAX_COVER_DISTANCE } == true
		}
	}

	private fun link(
		fingerprint: WorkFingerprint,
		workId: Long,
		method: ResolutionMethod,
		reporterId: String?,
	): Resolution {
		val linkedWorkId = if (reporterId == null) {
			repository.linkAlias(
				source = fingerprint.source,
				sourceKey = fingerprint.sourceKey,
				workId = workId,
				confidence = method.confidence,
				evidence = method.name.lowercase(),
			)
		} else {
			repository.observeAlias(
				fingerprint.source,
				fingerprint.sourceKey,
				reporterId,
				workId,
				listOf(fingerprint.title).flatMap(TitleNormalizer::keys),
			)
			workId
		}
		observeTitles(linkedWorkId, fingerprint)
		fingerprint.coverPHash?.let { repository.storeCoverHash(linkedWorkId, fingerprint.source, it) }
		return Resolution(linkedWorkId, method, created = false)
	}

	/**
	 * Write-back: every rendering this source used becomes part of the work.
	 *
	 * This is what makes the system improve with use. The first user of an unusual spelling pays for
	 * a fuzzy match; everyone after them gets an exact hit, forever. Stored at low weight so an
	 * observed title can speed up lookups without ever being strong enough to drive a merge.
	 */
	private fun observeTitles(workId: Long, fingerprint: WorkFingerprint) {
		val known = repository.titlesOf(workId)
		val unseen = fingerprint.allTitles.filter { title ->
			TitleNormalizer.keys(title).none { it in known }
		}
		if (unseen.isNotEmpty()) {
			repository.addTitles(workId, unseen.map { TitleToStore(it, kind = "source_observed", weight = 0.3) })
		}
	}

	private fun createFromCatalogue(
		fingerprint: WorkFingerprint,
		record: CatalogueRecord,
		reporterId: String?,
	): Resolution {
		// Catalogue payloads are external input too. Keep the repository strict so no caller can
		// overflow PostgreSQL SMALLINT, but do not turn one provider''s malformed year into a 500.
		val year = record.year?.takeIf(WorkLimits::isValidYear) ?: fingerprint.year
		if (reporterId != null) {
			return createObserved(
				fingerprint,
				reporterId,
				record.canonicalTitle,
				year,
				record.contentType ?: fingerprint.contentType,
				record.nsfw || fingerprint.nsfw,
				record.titles.map { TitleToStore(it, kind = "catalogue", weight = 1.0) } +
					fingerprint.allTitles.map { TitleToStore(it, kind = "source_observed", weight = 0.3) },
				record.externalIds,
				ResolutionMethod.CATALOGUE,
			)
		}
		val result = repository.createAndLinkWork(
			canonicalTitle = record.canonicalTitle,
			year = year,
			contentType = record.contentType ?: fingerprint.contentType,
			nsfw = record.nsfw || fingerprint.nsfw,
			titles = record.titles.map { TitleToStore(it, kind = "catalogue", weight = 1.0) } +
				fingerprint.allTitles.map { TitleToStore(it, kind = "source_observed", weight = 0.3) },
			externalIds = record.externalIds,
			source = fingerprint.source,
			sourceKey = fingerprint.sourceKey,
			confidence = ResolutionMethod.CATALOGUE.confidence,
			evidence = record.provider,
			coverPHash = fingerprint.coverPHash,
		)
		val method = when {
			result.aliasAlreadyExisted -> ResolutionMethod.ALIAS
			else -> ResolutionMethod.CATALOGUE
		}
		if (result.created) {
			log.debug("Created work {} from {} with {} titles", result.workId, record.provider, record.titles.size)
		}
		return Resolution(result.workId, method, result.created)
	}

	private fun createFromFingerprint(fingerprint: WorkFingerprint, reporterId: String?): Resolution {
		if (reporterId != null) {
			return createObserved(
				fingerprint,
				reporterId,
				fingerprint.title,
				fingerprint.year,
				fingerprint.contentType,
				fingerprint.nsfw,
				fingerprint.allTitles.map { TitleToStore(it, kind = "source_observed", weight = 0.3) },
				emptyMap(),
				ResolutionMethod.CREATED,
			)
		}
		val result = repository.createAndLinkWork(
			canonicalTitle = fingerprint.title,
			year = fingerprint.year,
			contentType = fingerprint.contentType,
			nsfw = fingerprint.nsfw,
			titles = fingerprint.allTitles.map { TitleToStore(it, kind = "source_observed") },
			externalIds = emptyMap(),
			source = fingerprint.source,
			sourceKey = fingerprint.sourceKey,
			confidence = ResolutionMethod.CREATED.confidence,
			evidence = "fingerprint",
			coverPHash = fingerprint.coverPHash,
		)
		return Resolution(
			result.workId,
			if (result.aliasAlreadyExisted) ResolutionMethod.ALIAS else ResolutionMethod.CREATED,
			result.created,
		)
	}

	private fun createObserved(
		fingerprint: WorkFingerprint,
		reporterId: String,
		canonicalTitle: String,
		year: Int?,
		contentType: String?,
		nsfw: Boolean,
		titles: List<TitleToStore>,
		externalIds: Map<String, String>,
		method: ResolutionMethod,
	): Resolution {
		val workId = repository.createObservedWork(
			canonicalTitle = canonicalTitle,
			year = year,
			contentType = contentType,
			nsfw = nsfw,
			titles = titles,
			externalIds = externalIds,
			source = fingerprint.source,
			sourceKey = fingerprint.sourceKey,
			reporterId = reporterId,
			titleKeys = listOf(fingerprint.title).flatMap(TitleNormalizer::keys),
			coverPHash = fingerprint.coverPHash,
		)
		return Resolution(workId, method, created = true)
	}

	companion object {

		/** Matches the app's `CoverHash`: below this, two covers are the same artwork. */
		const val MAX_COVER_DISTANCE = 12
		const val MIN_TRUSTED_TITLE_WEIGHT = 0.5

		/**
		 * Candidate **generation**, deliberately loose.
		 *
		 * Measured, not guessed: "kanojo mo kanojo girlfriend" and "kanojo mo kanojo" - the same work
		 * with a subtitle one source adds - score 0.48. A tighter net never surfaces the candidate, so
		 * the cover hash never gets a chance to decide, and the whole point of having a second signal
		 * is lost. Generation is loose; acceptance is strict.
		 */
		const val CANDIDATE_THRESHOLD = 0.40

		/**
		 * Acceptance on a title alone, with nothing corroborating it. High on purpose: below this the
		 * candidate is dropped rather than linked, because a wrong merge cannot be walked back the way
		 * a missed one can.
		 */
		const val TITLE_ONLY_THRESHOLD = 0.85

		const val YEAR_TOLERANCE = 2
		const val MAX_CONCURRENT_RESOLUTIONS = 8
		const val MAX_QUEUED_RESOLUTIONS = 32
		const val ALIAS_LOCK_STRIPES = 256
		const val MAX_QUEUED_PER_ALIAS_STRIPE = 8

		private val KNOWN_TYPES = setOf("manga", "manhwa", "manhua", "novel", "oneshot", "doujinshi")

		fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
	}
}

class WorkResolutionOverloaded : RuntimeException("work resolution capacity exhausted")
