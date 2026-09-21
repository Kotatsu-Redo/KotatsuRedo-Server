package io.kotatsuredo.server.catalogue

import io.kotatsuredo.server.works.TitleNormalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

private val log = LoggerFactory.getLogger("CatalogueLookup")

/**
 * External catalogues are consulted **on a miss, never on a schedule**.
 *
 * The earlier plan was to bulk-import a whole catalogue up front. That is a lot of requests for data
 * nobody asks for - most of any catalogue is works this userbase will never open - and it makes the
 * server's usefulness depend on a third party staying available for a long import. AniList going
 * dark mid-build made the point better than any argument could.
 *
 * So: the local catalogue answers first, an external lookup happens only for a work genuinely never
 * seen before, and the result is cached permanently. External traffic is therefore bounded by
 * *distinct works ever opened*, which converges quickly, rather than by catalogue size or user count.
 */
class CatalogueLookup(
	private val providers: List<CatalogueProvider>,
	private val maxConcurrent: Int = MAX_CONCURRENT,
	private val maxQueued: Int = MAX_QUEUED,
) {

	private val gate = Semaphore(maxConcurrent)
	private val admission = Semaphore(maxConcurrent + maxQueued)

	/**
	 * Titles already looked up and found nothing, so a manga missing from every catalogue costs one
	 * round trip ever rather than one per user who opens it. Bounded, and cheap to lose on restart.
	 */
	private val misses = ConcurrentHashMap.newKeySet<String>()
	private val inFlight = ConcurrentHashMap<String, Deferred<CatalogueOutcome>>()

	/** The record, or null whether nobody lists it or nobody answered. See [lookupOutcome]. */
	suspend fun lookup(
		title: String,
		year: Int? = null,
		contentType: String? = null,
	): CatalogueRecord? = (lookupOutcome(title, year, contentType) as? CatalogueOutcome.Found)?.record

	/**
	 * The full answer, for callers that must tell "no catalogue lists this" from "no catalogue
	 * answered". The enricher is one: the first means the work stands on its own title for good, the
	 * second means try again later, and treating them alike loses works to a bad minute.
	 */
	suspend fun lookupOutcome(
		title: String,
		year: Int? = null,
		contentType: String? = null,
	): CatalogueOutcome = coroutineScope {
		val query = searchTitle(title)
		// The content type is part of the question, not a filter on the answer: the same title asked
		// about as a novel and as a manhwa can have two different right answers, and one must not
		// negative-cache or share an in-flight lookup with the other.
		val key = TitleNormalizer.normalize(query).let { normalized ->
			if (normalized.isEmpty()) normalized else "$normalized|${contentType?.lowercase().orEmpty()}"
		}
		if (key.isEmpty() || key in misses) return@coroutineScope CatalogueOutcome.NotListed
		inFlight[key]?.let { return@coroutineScope it.await() }
		// Overload must not look like a catalogue miss: the resolver would otherwise create and retain
		// a provisional work even though no provider was consulted.
		if (!admission.tryAcquire()) throw CatalogueLookupOverloaded()
		val mine = async(start = CoroutineStart.LAZY) {
			// A lookup that ran out of time has not answered either, and the queue above is sized to
			// drain well inside this deadline. Calling it a miss would leave a provisional work behind -
			// the exact split a slow provider used to cause - so it refuses instead, which costs the
			// caller one retry and nothing permanent.
			withTimeoutOrNull(LOOKUP_DEADLINE_MS) {
				lookupOnce(key, query, year, contentType)
			} ?: throw CatalogueLookupOverloaded()
		}
		val active = inFlight.putIfAbsent(key, mine) ?: mine
		if (active !== mine) {
			mine.cancel()
			admission.release()
		}
		try {
			active.await()
		} finally {
			if (active === mine) {
				inFlight.remove(key, mine)
				admission.release()
			}
		}
	}

	private suspend fun lookupOnce(
		key: String,
		title: String,
		year: Int?,
		contentType: String?,
	): CatalogueOutcome {
		var silent = false
		val found = mutableListOf<CatalogueRecord>()
		gate.withPermit {
			// Another caller may have completed the same miss while this one waited for the gate.
			if (key in misses) return CatalogueOutcome.NotListed
			// Every provider, not the first that answers. Each one knows identifiers the others do not
			// - MangaDex carries the MyAnimeList and AniList ids, MangaUpdates the associated names -
			// and it is having *all* of them that lets two works found by two sources turn out to be
			// one. Stopping at the first answer is how a work ends up with a single id and no way to
			// meet its own duplicate.
			providers.forEach { provider ->
				val record = runCatching { provider.lookup(title, year, contentType) }
					.onFailure { error ->
						// The deadline above ran out. That is not this provider failing, and carrying
						// on would only start the next one inside a coroutine already cancelled.
						if (error is CancellationException) throw error
						// A provider that could not answer has said nothing about this title. Counting
						// that as "not in any catalogue" is how an outage turns into a permanent
						// provisional work that no other source can ever match.
						silent = true
						when (error) {
							is CatalogueUnavailable -> log.debug("Catalogue {} is unavailable", provider.name)
							else -> log.warn("Catalogue {} lookup failed", provider.name, error)
						}
					}
					.getOrNull()
				if (record != null) found += record
			}
		}

		if (found.isNotEmpty()) {
			val combined = combine(found)
			log.debug(
				"Catalogue resolved a title via {} ({} titles, {} ids)",
				found.joinToString("+") { it.provider }, combined.titles.size, combined.externalIds.size,
			)
			// Partial when somebody was unreachable: what came back is worth keeping, and the work is
			// worth asking about again once they are back.
			return CatalogueOutcome.Found(combined, partial = silent)
		}
		if (silent) return CatalogueOutcome.Unavailable
		if (misses.size < MAX_NEGATIVE_CACHE) misses.add(key)
		return CatalogueOutcome.NotListed
	}

	/**
	 * Folds every answer into one record.
	 *
	 * The first provider to answer names the work - order is the preference - and the rest contribute
	 * what they alone know: their titles, and above all their identifiers.
	 */
	private fun combine(records: List<CatalogueRecord>): CatalogueRecord {
		val primary = records.first()
		if (records.size == 1) return primary
		return primary.copy(
			titles = records.flatMap { it.titles }.distinct(),
			// An earlier provider's id wins a collision, so the answer does not change with timing.
			externalIds = buildMap { records.forEach { record -> record.externalIds.forEach(::putIfAbsent) } },
			year = records.firstNotNullOfOrNull { it.year },
			contentType = records.firstNotNullOfOrNull { it.contentType },
			// Any catalogue calling it adult is enough; none of them mark it by accident.
			nsfw = records.any { it.nsfw },
			relations = records.flatMap { it.relations }.distinct(),
		)
	}

	companion object {
		/**
		 * The part of a title worth searching for.
		 *
		 * Some sources write every name a work has into its title - `Main (異世界…/ Alt Two/ Alt
		 * Three/ …)`, often cut off mid-list. No catalogue search matches that, no candidate's title
		 * normalizes to it, and asking every provider to try made these lookups run into the deadline
		 * until the enricher gave up on them. A parenthesis holding a `/`-separated list is that list;
		 * one without a slash (`Title (Official)`, `Fate/Zero (2011)`) is left alone.
		 */
		fun searchTitle(title: String): String {
			val open = title.indexOf('(')
			if (open <= 0) return title
			if ('/' !in title.substring(open + 1).substringBefore(')')) return title
			val main = title.substring(0, open).trim()
			return if (main.length >= MIN_SEARCH_TITLE) main else title
		}

		private const val MIN_SEARCH_TITLE = 2

		/**
		 * Politeness to catalogues that are doing us a favour by being open and unauthenticated. This
		 * is a courtesy to someone else's server, so it is deliberately not scaled to our hardware.
		 */
		private const val MAX_CONCURRENT = 4

		/**
		 * Where the 429s came from: this pool, not the database. Refusing a queued lookup turns an
		 * external catalogue being busy into a missing rating row, so the queue is now deep enough to
		 * absorb a burst and shallow enough to drain inside [LOOKUP_DEADLINE_MS] - at four in flight
		 * and about a second each, roughly 120 lookups fit in the deadline, so 96 waits rather than
		 * times out.
		 */
		private const val MAX_QUEUED = 96
		private const val MAX_NEGATIVE_CACHE = 50_000
		private const val LOOKUP_DEADLINE_MS = 30_000L
	}
}

/** What a completed lookup can say. */
sealed interface CatalogueOutcome {
	/**
	 * @param partial true when at least one provider could not be reached, so the record is what the
	 *  rest knew. Worth applying and worth asking again later, because the missing provider is often
	 *  the one holding the identifier that joins this work to its duplicate.
	 */
	data class Found(val record: CatalogueRecord, val partial: Boolean = false) : CatalogueOutcome

	/** Every provider answered and none of them has this title. Worth remembering. */
	data object NotListed : CatalogueOutcome

	/** At least one provider could not be reached or replied unusably. Worth retrying. */
	data object Unavailable : CatalogueOutcome
}

class CatalogueLookupOverloaded : RuntimeException("catalogue lookup capacity exhausted")

/**
 * The real fetcher. Returns null rather than throwing on every failure mode, because a catalogue
 * being slow, rate-limiting or down must degrade resolution, never fail a user's request.
 */
class JdkHttpFetcher(
	private val userAgent: String = "kotatsuredo-server/0.1 (+https://github.com/Kotatsu-Redo)",
	private val client: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		// Provider endpoints are fixed. Following an upstream redirect could turn a compromised
		// provider into a request to a private or link-local address.
		.followRedirects(HttpClient.Redirect.NEVER)
		.build(),
) : HttpFetcher {
	private data class FetchResponse(val statusCode: Int, val retryAfter: String?, val body: String?)

	override suspend fun fetch(url: String, jsonBody: String?, accept: String): String? {
		repeat(MAX_ATTEMPTS) { attempt ->
			val builder = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", userAgent)
				.header("Accept", accept)
				.timeout(Duration.ofSeconds(20))
			if (jsonBody != null) {
				builder.header("Content-Type", "application/json")
				builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
			} else {
				builder.GET()
			}

			val response = send(builder.build())

			when {
				response == null -> delay(backoff(attempt))
				response.statusCode in 200..299 -> return response.body
				// 429 and 5xx are worth one more try; anything else is an answer, even if unwelcome.
				response.statusCode == 429 || response.statusCode in 500..599 -> {
					val retryAfter = response.retryAfter?.toLongOrNull()?.times(1000) ?: backoff(attempt)
					delay(minOf(retryAfter, MAX_BACKOFF_MS))
				}

				else -> {
					log.debug("Catalogue request returned {}", response.statusCode)
					return null
				}
			}
		}
		return null
	}

	private suspend fun send(request: HttpRequest): FetchResponse? {
		val response: HttpResponse<InputStream>? = suspendCancellableCoroutine { continuation ->
			val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
			continuation.invokeOnCancellation { future.cancel(true) }
			future.whenComplete { response, error ->
				if (continuation.isActive) {
					continuation.resume(if (error == null) response else null)
				}
			}
		}
		if (response == null) return null
		return withContext(Dispatchers.IO) {
			response.body().use { input ->
				val body = if (response.statusCode() in 200..299) {
					val bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1)
					if (bytes.size > MAX_RESPONSE_BYTES) null else bytes.toString(Charsets.UTF_8)
				} else {
					null
				}
				FetchResponse(
					response.statusCode(),
					response.headers().firstValue("Retry-After").orElse(null),
					body,
				)
			}
		}
	}

	private fun backoff(attempt: Int): Long = minOf(500L shl attempt, MAX_BACKOFF_MS)

	private companion object {
		const val MAX_ATTEMPTS = 3
		const val MAX_BACKOFF_MS = 8_000L
		const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
	}
}
