package io.kotatsuredo.server.works

import io.kotatsuredo.server.catalogue.CatalogueLookup
import io.kotatsuredo.server.catalogue.CatalogueRecord
import org.slf4j.LoggerFactory

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
) {

	suspend fun resolve(fingerprint: WorkFingerprint): Resolution {
		// 1. Alias. O(1) and the overwhelming majority of traffic once a source is warm.
		repository.findByAlias(fingerprint.source, fingerprint.sourceKey)?.let {
			return Resolution(it, ResolutionMethod.ALIAS, created = false)
		}

		// 2. External id. A scrobbler link is as good as it gets.
		repository.findByExternalIds(fingerprint.externalIds)?.let { workId ->
			return link(fingerprint, workId, ResolutionMethod.EXTERNAL_ID)
		}

		val keys = fingerprint.allTitles.flatMap(TitleNormalizer::keys).toSet()

		// 3. Exact normalized key. This is where a catalogue's renderings pay off: a source using a
		//    different spelling is usually not a matching problem at all, because the work is already
		//    indexed under that spelling.
		repository.findByTitleKeys(keys)
			.firstOrNull { candidate -> isCompatible(fingerprint, candidate) }
			?.let { return link(fingerprint, it, ResolutionMethod.EXACT_TITLE) }

		// 4. Fuzzy, corroborated. Trigram narrows to a handful of candidates; the cover hash decides.
		//    The cover is never searched globally - only compared against candidates the title already
		//    produced, which is why no hash index is needed (PLAN.md §2.4).
		val candidates = keys.flatMap { key -> repository.findSimilarTitles(key, candidateThreshold) }
			.groupBy({ it.first }, { it.second })
			.mapValues { (_, scores) -> scores.max() }
			.filterKeys { candidate -> isCompatible(fingerprint, candidate) }

		if (candidates.isNotEmpty()) {
			verifyByCover(fingerprint, candidates.keys)?.let {
				return link(fingerprint, it, ResolutionMethod.TITLE_AND_COVER)
			}
			val best = candidates.maxByOrNull { it.value }
			if (best != null && best.value >= TITLE_ONLY_THRESHOLD) {
				// Linked but flagged: a title that merely looks similar, with nothing backing it up.
				return link(fingerprint, best.key, ResolutionMethod.FUZZY_TITLE)
			}
		}

		// 5. Nothing local knows it. Ask a catalogue once, and store what it says.
		catalogue?.lookup(fingerprint.title, fingerprint.year)?.let { record ->
			return createFromCatalogue(fingerprint, record)
		}

		// 6. Create from what the client itself reported. The catalogue is an enrichment, never a gate.
		return createFromFingerprint(fingerprint)
	}

	/**
	 * Rejects a candidate that a title match would otherwise accept.
	 *
	 * The sequence-marker check is the important half: "Tower of God" and "Tower of God Part 2"
	 * normalize differently, but a fuzzy match would happily pair them, and merging a sequel into its
	 * prequel leaks spoilers into a thread where nobody has read that far.
	 */
	private fun isCompatible(fingerprint: WorkFingerprint, workId: Long): Boolean {
		val metadata = repository.metadataOf(workId) ?: return false
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

	private fun link(fingerprint: WorkFingerprint, workId: Long, method: ResolutionMethod): Resolution {
		repository.linkAlias(
			source = fingerprint.source,
			sourceKey = fingerprint.sourceKey,
			workId = workId,
			confidence = method.confidence,
			evidence = method.name.lowercase(),
		)
		observeTitles(workId, fingerprint)
		fingerprint.coverPHash?.let { repository.storeCoverHash(workId, fingerprint.source, it) }
		return Resolution(workId, method, created = false)
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

	private fun createFromCatalogue(fingerprint: WorkFingerprint, record: CatalogueRecord): Resolution {
		val workId = repository.createWork(
			canonicalTitle = record.canonicalTitle,
			year = record.year ?: fingerprint.year,
			contentType = record.contentType ?: fingerprint.contentType,
			nsfw = record.nsfw || fingerprint.nsfw,
		)
		repository.addTitles(
			workId,
			record.titles.map { TitleToStore(it, kind = "catalogue", weight = 1.0) } +
				fingerprint.allTitles.map { TitleToStore(it, kind = "source_observed", weight = 0.3) },
		)
		repository.addExternalIds(workId, record.externalIds + fingerprint.externalIds)
		repository.linkAlias(
			fingerprint.source, fingerprint.sourceKey, workId,
			ResolutionMethod.CATALOGUE.confidence, record.provider,
		)
		fingerprint.coverPHash?.let { repository.storeCoverHash(workId, fingerprint.source, it) }
		log.debug("Created work {} from {} with {} titles", workId, record.provider, record.titles.size)
		return Resolution(workId, ResolutionMethod.CATALOGUE, created = true)
	}

	private fun createFromFingerprint(fingerprint: WorkFingerprint): Resolution {
		val workId = repository.createWork(
			canonicalTitle = fingerprint.title,
			year = fingerprint.year,
			contentType = fingerprint.contentType,
			nsfw = fingerprint.nsfw,
		)
		repository.addTitles(workId, fingerprint.allTitles.map { TitleToStore(it, kind = "source_observed") })
		repository.addExternalIds(workId, fingerprint.externalIds)
		repository.linkAlias(
			fingerprint.source, fingerprint.sourceKey, workId,
			ResolutionMethod.CREATED.confidence, "fingerprint",
		)
		fingerprint.coverPHash?.let { repository.storeCoverHash(workId, fingerprint.source, it) }
		return Resolution(workId, ResolutionMethod.CREATED, created = true)
	}

	companion object {

		/** Matches the app's `CoverHash`: below this, two covers are the same artwork. */
		const val MAX_COVER_DISTANCE = 12

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

		private val KNOWN_TYPES = setOf("manga", "manhwa", "manhua", "novel", "oneshot", "doujinshi")

		fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
	}
}
