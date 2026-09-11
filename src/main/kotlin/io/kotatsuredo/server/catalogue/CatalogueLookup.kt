package io.kotatsuredo.server.catalogue

import io.kotatsuredo.server.works.TitleNormalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

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
) {

	private val gate = Semaphore(maxConcurrent)

	/**
	 * Titles already looked up and found nothing, so a manga missing from every catalogue costs one
	 * round trip ever rather than one per user who opens it. Bounded, and cheap to lose on restart.
	 */
	private val misses = ConcurrentHashMap.newKeySet<String>()

	suspend fun lookup(title: String, year: Int? = null): CatalogueRecord? {
		val key = TitleNormalizer.normalize(title)
		if (key.isEmpty() || key in misses) return null

		gate.withPermit {
			providers.forEach { provider ->
				val record = runCatching { provider.lookup(title, year) }
					.onFailure { log.warn("Catalogue {} failed for '{}'", provider.name, title, it) }
					.getOrNull()
				if (record != null) {
					log.debug("Resolved '{}' via {} ({} titles)", title, provider.name, record.titles.size)
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
		const val MAX_NEGATIVE_CACHE = 50_000
	}
}

/**
 * The real fetcher. Returns null rather than throwing on every failure mode, because a catalogue
 * being slow, rate-limiting or down must degrade resolution, never fail a user's request.
 */
class JdkHttpFetcher(
	private val userAgent: String = "kotatsuredo-server/0.1 (+https://github.com/Kotatsu-Redo)",
	private val client: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build(),
) : HttpFetcher {

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

			val response = runCatching { client.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }
				.getOrNull()

			when {
				response == null -> delay(backoff(attempt))
				response.statusCode() in 200..299 -> return response.body()
				// 429 and 5xx are worth one more try; anything else is an answer, even if unwelcome.
				response.statusCode() == 429 || response.statusCode() in 500..599 -> {
					val retryAfter = response.headers().firstValue("Retry-After")
						.map { it.toLongOrNull()?.times(1000) ?: backoff(attempt) }
						.orElse(backoff(attempt))
					delay(minOf(retryAfter, MAX_BACKOFF_MS))
				}

				else -> {
					log.debug("Catalogue request to {} returned {}", url, response.statusCode())
					return null
				}
			}
		}
		return null
	}

	private fun backoff(attempt: Int): Long = minOf(500L shl attempt, MAX_BACKOFF_MS)

	private companion object {
		const val MAX_ATTEMPTS = 3
		const val MAX_BACKOFF_MS = 8_000L
	}
}
