package io.kotatsuredo.server.works

import io.kotatsuredo.server.catalogue.CatalogueLookup
import io.kotatsuredo.server.catalogue.CatalogueLookupOverloaded
import io.kotatsuredo.server.catalogue.CatalogueOutcome
import io.kotatsuredo.server.ratings.RatingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("WorkEnricher")

/**
 * Describes works after the fact, so no reader ever waits on a catalogue.
 *
 * Resolution used to consult Kitsu or MangaUpdates while the request was open. That put a third
 * party's availability on the hot path: with four lookups in flight and a queue behind them, a busy
 * minute became a 429, and the app turns a 429 into a details screen with no rating row at all. The
 * work is now created from what the source said and queued; this fills in the catalogue's titles and
 * identifiers a moment later.
 *
 * The interesting case is the last one below: when the catalogue's identifiers turn out to belong to
 * a work we already had, the provisional one is merged away. The client is told through the ordinary
 * `work_moved` path, which the app already follows for ratings and comments.
 */
class WorkEnricher(
	private val repository: WorkRepository,
	private val catalogue: CatalogueLookup?,
	private val ratings: RatingService? = null,
	private val batchSize: Int = BATCH_SIZE,
	/** Off in tests that want an empty queue to stay empty. */
	private val seedOldWorks: Boolean = true,
) {

	data class Report(
		val enriched: Int,
		val merged: Int,
		val unlisted: Int,
		val deferred: Int,
		/** Enriched from an incomplete answer, and queued to be asked again. */
		val partial: Int = 0,
		/** Works from before any catalogue answered, queued by the running backfill. */
		val seeded: Int = 0,
	)

	suspend fun drainOnce(): Report {
		if (catalogue == null) return Report(0, 0, 0, 0)
		val seeded = topUpBackfill()
		var enriched = 0
		var merged = 0
		var unlisted = 0
		var deferred = 0
		var partial = 0

		repository.dueEnrichments(batchSize).forEach { pending ->
			val outcome = try {
				catalogue.lookupOutcome(pending.title, pending.year, pending.contentType)
			} catch (_: CatalogueLookupOverloaded) {
				// Nobody answered. Retrying later is the whole reason this is a table.
				defer(pending)
				deferred++
				return@forEach
			} catch (error: Exception) {
				log.warn("Enrichment lookup failed for work {}", pending.workId, error)
				defer(pending)
				deferred++
				return@forEach
			}

			val record = when (outcome) {
				is CatalogueOutcome.Found -> outcome.record.also {
					// Somebody was unreachable, so this is what the rest knew. Keep it, and ask again
					// later: the provider that was down is often the one holding the id that would have
					// joined this work to its duplicate.
					if (outcome.partial) partial++
				}

				// Nobody could be reached. Saying "not in any catalogue" here would strand the work on
				// its source's own title forever because of one bad minute.
				CatalogueOutcome.Unavailable -> {
					defer(pending)
					deferred++
					return@forEach
				}

				// Genuinely in no catalogue. The work keeps the source's own title, which is all anyone
				// ever knew about it, and the duplicate sweep can still join it to its twins by title.
				CatalogueOutcome.NotListed -> {
					repository.finishEnrichment(pending.workId)
					unlisted++
					return@forEach
				}
			}

			val existing = repository.findByExternalIds(record.externalIds)
			if (existing != null && existing != pending.workId) {
				runCatching {
					repository.mergeWorks(
						from = pending.workId,
						into = existing,
						reason = MERGE_REASON,
					)
					ratings?.recompute(existing)
					ratings?.recompute(pending.workId)
				}.onFailure { log.warn("Failed to merge enriched work {} into {}", pending.workId, existing, it) }
				repository.finishEnrichment(pending.workId)
				merged++
				log.info("Enrichment merged work {} into {} ({})", pending.workId, existing, record.provider)
				return@forEach
			}

			runCatching {
				repository.applyCatalogue(
					workId = pending.workId,
					canonicalTitle = record.canonicalTitle,
					year = record.year?.takeIf(WorkLimits::isValidYear),
					contentType = record.contentType,
					nsfw = record.nsfw,
					titles = record.titles.map { TitleToStore(it, kind = "catalogue", weight = 1.0) },
					externalIds = record.externalIds,
				)
			}.onSuccess {
				enriched++
				if (outcome is CatalogueOutcome.Found && outcome.partial) {
					repository.scheduleRecheck(pending.workId, PARTIAL_RETRY_SECONDS)
				}
			}
				.onFailure {
					log.warn("Failed to apply catalogue data to work {}", pending.workId, it)
					defer(pending)
					deferred++
				}
		}
		return Report(enriched, merged, unlisted, deferred, partial, seeded)
	}

	/**
	 * Keeps the queue fed from the works that predate any catalogue ever answering.
	 *
	 * Those works are the ones nobody else's source can match, so seeding them is not a one-off
	 * cleanup - it runs until there are none left and then costs a single count per sweep. Topping up
	 * only when the queue has drained keeps the pace at whatever the enricher can actually do, which
	 * is what keeps this polite: the providers see the same steady trickle either way.
	 */
	private fun topUpBackfill(): Int {
		if (!seedOldWorks) return 0
		if (repository.dueEnrichmentCount() > TOP_UP_BELOW) return 0
		val queued = repository.enqueueBackfill(TOP_UP_BATCH)
		if (queued > 0) log.info("Queued {} works that no catalogue has described yet", queued)
		return queued
	}

	private fun defer(pending: PendingEnrichment) {
		if (pending.attempts >= MAX_ATTEMPTS) {
			// Long past the point where another try is worth a request. The work stands on its own
			// title, and the duplicate sweep remains its way back.
			log.info("Giving up on enriching work {} after {} attempts", pending.workId, pending.attempts)
			repository.finishEnrichment(pending.workId)
			return
		}
		val backoff = minOf(BASE_BACKOFF_SECONDS shl pending.attempts.coerceAtMost(MAX_BACKOFF_SHIFT), MAX_BACKOFF_SECONDS)
		repository.retryEnrichmentLater(pending.workId, backoff)
	}

	fun schedule(scope: CoroutineScope, interval: Duration = INTERVAL) = scope.launch(Dispatchers.IO) {
		while (isActive) {
			val report = runCatching { drainOnce() }
				.onFailure { log.warn("Enrichment sweep failed", it) }
				.getOrNull()
			if (report != null && report != Report(0, 0, 0, 0)) log.info("Enrichment: {}", report)
			// A queue with something in it is drained back to back; an empty one waits.
			val worked = report != null && report.enriched + report.merged + report.unlisted + report.seeded > 0
			delay(if (worked) IDLE_GAP else interval)
		}
	}

	private companion object {
		/** Small: each entry is an external request, and the catalogue pool is deliberately narrow. */
		const val BATCH_SIZE = 10
		const val MAX_ATTEMPTS = 12
		const val BASE_BACKOFF_SECONDS = 30L
		const val MAX_BACKOFF_SHIFT = 6
		const val MAX_BACKOFF_SECONDS = 3_600L
		const val MERGE_REASON = "catalogue_enrichment"

		/** Long enough that a provider having a bad hour is over before the work is asked about again. */
		const val PARTIAL_RETRY_SECONDS = 6 * 3_600L

		/** Top up only once the queue has drained, so the pace stays the enricher's own. */
		const val TOP_UP_BELOW = 20
		const val TOP_UP_BATCH = 200
		val INTERVAL = 30.seconds
		val IDLE_GAP = 1.seconds
	}
}
