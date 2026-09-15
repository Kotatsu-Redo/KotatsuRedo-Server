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
	private val inFlight = ConcurrentHashMap<String, Deferred<CatalogueRecord?>>()

	suspend fun lookup(title: String, year: Int? = null): CatalogueRecord? = coroutineScope {
		val key = TitleNormalizer.normalize(title)
		if (key.isEmpty() || key in misses) return@coroutineScope null
		inFlight[key]?.let { return@coroutineScope it.await() }
		// Overload must not look like a catalogue miss: the resolver would otherwise create and retain
		// a provisional work even though no provider was consulted.
		if (!admission.tryAcquire()) throw CatalogueLookupOverloaded()
		val mine = async(start = CoroutineStart.LAZY) {
			withTimeoutOrNull(LOOKUP_DEADLINE_MS) { lookupOnce(key, title, year) }
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

	private suspend fun lookupOnce(key: String, title: String, year: Int?): CatalogueRecord? {
		gate.withPermit {
			// Another caller may have completed the same miss while this one waited for the gate.
			if (key in misses) return null
			providers.forEach { provider ->
				val record = runCatching { provider.lookup(title, year) }
					.onFailure { log.warn("Catalogue {} lookup failed", provider.name, it) }
					.getOrNull()
				if (record != null) {
					log.debug("Catalogue resolved a title via {} ({} titles)", provider.name, record.titles.size)
					return record
				}
			}
		}

		if (misses.size < MAX_NEGATIVE_CACHE) misses.add(key)
		return null
	}

	private companion object {
		/** Politeness to catalogues that are doing us a favour by being open and unauthenticated. */
		const val MAX_CONCURRENT = 2
		const val MAX_QUEUED = 16
		const val MAX_NEGATIVE_CACHE = 50_000
		const val LOOKUP_DEADLINE_MS = 30_000L
	}
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

	override suspend fun fetch(url: String, jsonBody: String?): String? {
		repeat(MAX_ATTEMPTS) { attempt ->
			val builder = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", userAgent)
				.header("Accept", "application/json")
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
