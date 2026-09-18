package io.kotatsuredo.server.works

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
	 *
	 * **No catalogue is consulted here.** A work nothing local recognises is created from what the
	 * source said and queued for [WorkEnricher], because waiting on a third party inside the request
	 * made a busy external pool look, to the reader, like a manga with no community data. The
	 * catalogue's titles and identifiers arrive a moment later, and merge the work away if they turn
	 * out to name one we already had.
	 */
	suspend fun resolve(fingerprint: WorkFingerprint, reporterId: String? = null): Resolution {
		val lockIndex = Math.floorMod(31 * fingerprint.source.hashCode() + fingerprint.sourceKey.hashCode(), aliasLocks.size)
		return withAliasCapacity(lockIndex) {
			withResolutionCapacity {
				resolveLocal(fingerprint, reporterId) ?: createFromFingerprint(fingerprint, reporterId).also {
					if (it.created) {
						repository.enqueueEnrichment(
							it.workId,
							fingerprint.title,
							fingerprint.year,
							fingerprint.contentType,
						)
					}
				}
			}
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

	/** The local rungs of the ladder. A null result means the work has never been seen here. */
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
			// Repeat opens stay on the (source, key, account) primary-key path. The wider account-title
			// lookup below is needed only when this source entry has not been observed before.
			repository.observedAlias(fingerprint.source, fingerprint.sourceKey, reporterId, keys)?.let { observed ->
				if (isCompatible(fingerprint, repository.metadataOf(observed))) {
					return Resolution(observed, ResolutionMethod.OBSERVATION, created = false)
				}
			}
			// Reuse this account's own exact-title observation across sources. The mapping remains private
			// to that account until authoritative catalogue data or the established-account quorum backs
			// it, so a remote client cannot poison how another account resolves the title.
			val own = repository.observedWorks(reporterId, keys)
			val ownMetadata = repository.metadataOf(own)
			own.firstOrNull { isCompatible(fingerprint, ownMetadata[it]) }?.let {
				repository.observeAlias(fingerprint.source, fingerprint.sourceKey, reporterId, it, keys)
				return Resolution(it, ResolutionMethod.OBSERVATION, created = false)
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
		//    A similar title alone is never accepted: near-identical names can still be separate works.
		if (fingerprint.coverPHash != null) {
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
			}
		}

		// Nothing local knows it: the caller creates it and queues it for enrichment.
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
	): Boolean = WorkCompatibility.isCompatible(
		fingerprint.title,
		fingerprint.year,
		fingerprint.contentType,
		metadata,
	)

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
		val result = repository.createObservedWork(
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
		return Resolution(result.workId, method, created = result.created)
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

		const val YEAR_TOLERANCE = WorkCompatibility.YEAR_TOLERANCE
		/**
		 * Kept at ~80% of the ten-connection pool on purpose: measured on the deployment box, raising
		 * the pool to 16 or 24 did not improve throughput and 24 made it worse, so the JDBC ceiling is
		 * the pool and this gate is what keeps resolution from owning all of it.
		 */
		const val MAX_CONCURRENT_RESOLUTIONS = 8

		/**
		 * Queue depth, not capacity. A burst that fits here waits a few milliseconds; the same burst
		 * against the old depth of 32 was refused outright, which is most of what the client sees as a
		 * missing rating row. The JDBC path measured ~1,300-2,800 requests a second against a live peak
		 * of roughly two, so the wait behind this queue is bounded by the gate above, not by hardware.
		 */
		const val MAX_QUEUED_RESOLUTIONS = 128
		const val ALIAS_LOCK_STRIPES = 256
		const val MAX_QUEUED_PER_ALIAS_STRIPE = 32

		fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
	}
}

class WorkResolutionOverloaded : RuntimeException("work resolution capacity exhausted")
