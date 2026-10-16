package io.kotatsuredo.server.routes

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.auth.enforceLimit
import io.kotatsuredo.server.auth.requireCaller
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.moderation.ModerationQueueRepository
import io.kotatsuredo.server.ops.ServerMetrics
import io.kotatsuredo.server.works.LinkOutcome
import io.kotatsuredo.server.works.Genres
import io.kotatsuredo.server.works.WorkFingerprint
import io.kotatsuredo.server.works.WorkLinker
import io.kotatsuredo.server.works.WorkLimits
import io.kotatsuredo.server.works.WorkResolver
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkResolutionOverloaded
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Serializable
data class FingerprintDto(
	val source: String,
	val key: String,
	val title: String,
	@SerialName("alt_titles") val altTitles: List<String> = emptyList(),
	val year: Int? = null,
	@SerialName("content_type") val contentType: String? = null,
	val nsfw: Boolean = false,
	@SerialName("cover_phash") val coverPHash: Long? = null,
	@SerialName("external_ids") val externalIds: Map<String, String> = emptyMap(),
	/** The source's own genre labels. Unrecognised ones are dropped server-side. */
	val genres: List<String> = emptyList(),
)

@Serializable
data class ResolveRequest(val works: List<FingerprintDto>)

@Serializable
data class WorkSourceDto(
	val source: String,
	val key: String,
	/** Distinct accounts that have seen this work here. Two is the floor for appearing at all. */
	val reporters: Int,
	@SerialName("last_seen") val lastSeen: String,
	val verified: Boolean,
)

@Serializable
data class SimilarWorkDto(
	@SerialName("work_id") val workId: String,
	val title: String,
	val year: Int? = null,
	@SerialName("content_type") val contentType: String? = null,
	val nsfw: Boolean = false,
	val genres: List<String> = emptyList(),
	@SerialName("shared_genres") val sharedGenres: Int = 0,
	/**
	 * How much the shared genres are worth: each one counts by how much rarer it is than the
	 * commonest genre the work asked about has. Sharing `villainess` says far more than sharing
	 * `action`, and a client blending several of these needs the weight, not just the count.
	 *
	 * Comparable within one response. Across responses it is not: the scale depends on the work
	 * asked about.
	 */
	val score: Double = 0.0,
	/** Where to read it: the sources that carry it, best corroborated first. */
	val sources: List<WorkSourceDto> = emptyList(),
)

@Serializable
data class SimilarWorksResponse(
	@SerialName("work_id") val workId: String,
	val similar: List<SimilarWorkDto>,
)

@Serializable
data class WorkGenresResponse(
	@SerialName("work_id") val workId: String,
	val genres: List<String>,
)

@Serializable
data class WorkSourcesResponse(
	@SerialName("work_id") val workId: String,
	val sources: List<WorkSourceDto>,
)

@Serializable
data class ResolvedDto(
	val source: String,
	val key: String,
	@SerialName("work_id") val workId: String,
	val method: String,
	val created: Boolean,
)

@Serializable
data class ResolveResponse(val resolved: List<ResolvedDto>)

@Serializable
data class LinkRequest(
	val from: FingerprintRef,
    val to: FingerprintRef,
	val evidence: String = "user_migration",
)

@Serializable
data class FingerprintRef(val source: String, val key: String)

@Serializable
data class LinkResponse(val outcome: String, @SerialName("work_id") val workId: String? = null)

fun Route.workRoutes(
	identities: IdentityService,
	resolver: WorkResolver,
	linker: WorkLinker,
	limiter: RateLimiter,
	works: WorkRepository,
	queues: ModerationQueueRepository? = null,
	similarWorks: SimilarWorks = SimilarWorks(works),
) = route("/works") {

	/**
	 * Works like this one.
	 *
	 * Item to item, and nothing else: the answer depends on the work asked about, never on who is
	 * asking or what else they have read. Blending several of these into a personal list is the
	 * app's job, on the device, where the reading history already lives and can stay.
	 */
	get("/{id}/similar") {
		val caller = call.requireCaller(identities)
		call.enforceLimit(limiter, RateLimiter.Bucket.GENERAL, caller.tier, caller.identity.id)
		val workId = call.resolveWorkId(works)

		// Genres change when a catalogue answers or a quorum lands, which is a scale of days.
		call.response.header(HttpHeaders.CacheControl, "private, max-age=21600")
		call.respond(similarWorks.responseFor(workId))
	}

	/**
	 * The work's genres, as the app would show them.
	 *
	 * Catalogue-backed terms first, then the ones readers' sources agree on. Everything here is in
	 * the server's closed vocabulary, so a client can switch on the values.
	 */
	get("/{id}/genres") {
		val caller = call.requireCaller(identities)
		call.enforceLimit(limiter, RateLimiter.Bucket.GENERAL, caller.tier, caller.identity.id)
		val workId = call.resolveWorkId(works)

		call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
		call.respond(WorkGenresResponse(workId.toString(), works.genresOf(workId)))
	}

	/**
	 * Which sources carry this work.
	 *
	 * The ranking the app had could say a source was good in general, never that it had *this*
	 * manga. This answers that from observations the resolver already stores - nothing new is
	 * collected - and the client orders the list by its own per-operation scores.
	 *
	 * Aggregate by construction: a source appears only once distinct accounts have seen the work
	 * there, so a reply can never describe one person's library.
	 */
	get("/{id}/sources") {
		val caller = call.requireCaller(identities)
		call.enforceLimit(limiter, RateLimiter.Bucket.GENERAL, caller.tier, caller.identity.id)
		val workId = call.resolveWorkId(works)

		// Coverage moves when somebody opens the work somewhere new, which is a scale of days.
		call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
		call.respond(
			WorkSourcesResponse(
				workId = workId.toString(),
				sources = works.sourcesFor(workId).map {
					WorkSourceDto(
						source = it.source,
						key = it.sourceKey,
						reporters = it.reporters,
						lastSeen = it.lastSeen.toString(),
						verified = it.verified,
					)
				},
			),
		)
	}

	/**
	 * The hot path, and always batched: a client resolving a screenful of manga should cost one
	 * request, not twenty (PLAN.md §3 Capacity).
	 */
	post("/resolve") {
		val caller = call.requireCaller(identities)

		val body = call.receive<ResolveRequest>()
		if (body.works.isEmpty()) throw ApiException(ApiError.BadRequest("works"))
		if (body.works.size > MAX_BATCH) throw ApiException(ApiError.BadRequest("too_many_works"))
		val titleInputs = body.works.sumOf { 1 + it.altTitles.size }
		if (titleInputs > MAX_TITLE_INPUTS_PER_BATCH) {
			throw ApiException(ApiError.BadRequest("too_many_titles"))
		}
		call.enforceLimit(
			limiter,
			RateLimiter.Bucket.WORK_MUTATION,
			caller.tier,
			caller.identity.id,
			// One manga, one unit. Charging per title made an app that sends the alt title its source
			// shows - which is most of them, and exactly what makes matching work - cost double, so a
			// new account's hundred an hour was really fifty manga of browsing. The batch is still
			// bounded by MAX_TITLE_INPUTS_PER_BATCH above.
			cost = body.works.size,
		)

		val resolved = try {
			coroutineScope { body.works.map { dto ->
				if (!dto.isValid()) throw ApiException(ApiError.BadRequest("work_fingerprint"))
				async {
					val resolution = resolver.resolve(
						WorkFingerprint(
							source = dto.source,
							sourceKey = dto.key,
							title = dto.title,
							altTitles = dto.altTitles,
							year = dto.year,
							contentType = dto.contentType,
							nsfw = dto.nsfw,
							coverPHash = dto.coverPHash,
							externalIds = emptyMap(),
							// Extra labels are dropped rather than refused; the vocabulary discards
							// most of them anyway.
							genres = dto.genres.take(Genres.MAX_GENRES_PER_WORK * 2),
						),
						reporterId = caller.identity.id,
					)
					ResolvedDto(
						source = dto.source,
						key = dto.key,
						workId = resolution.workId.toString(),
						method = resolution.method.name.lowercase(),
						created = resolution.created,
					)
				}
			}.awaitAll() }
		} catch (_: WorkResolutionOverloaded) {
			// Not a quota: the caller did nothing wrong and the same request will work in a moment.
			ServerMetrics.recordOverloaded("work_resolution")
			throw ApiException(ApiError.Overloaded(1, "work_resolution"))
		}
		call.respond(ResolveResponse(resolved))
	}

	/**
	 * A user migrated manga A to manga B, which is a human confirming they are the same work - the
	 * best signal in the system and the only one that may merge two existing works (§2.6).
	 */
	post("/link") {
		val caller = call.requireCaller(identities)
		if (caller.tier != TrustTier.ESTABLISHED) {
			throw ApiException(ApiError.Forbidden("established_account_required"))
		}
		call.enforceLimit(limiter, RateLimiter.Bucket.WORK_MUTATION, caller.tier, caller.identity.id)

		val body = call.receive<LinkRequest>()
		if (!body.from.isValid() || !body.to.isValid() || body.evidence.length > MAX_EVIDENCE) {
			throw ApiException(ApiError.BadRequest("link"))
		}
		val outcome = linker.link(
			a = body.from.source to body.from.key,
			b = body.to.source to body.to.key,
			evidence = body.evidence.sanitizedEvidence(),
			allowMerge = false,
		)

		val response = when (outcome) {
			is LinkOutcome.AlreadyLinked -> LinkResponse("already_linked", outcome.workId.toString())
			is LinkOutcome.Linked -> LinkResponse("linked", outcome.workId.toString())
			is LinkOutcome.Merged -> LinkResponse("merged", outcome.into.toString())
			is LinkOutcome.NeedsReview -> LinkResponse("needs_review")
			LinkOutcome.Unknown -> LinkResponse("unknown")
		}
		call.respond(
			status = if (outcome is LinkOutcome.Unknown) HttpStatusCode.NotFound else HttpStatusCode.OK,
			message = response,
		)
	}

	/**
	 * "This work is wrong" - two entries that should be one, or one that has swallowed two.
	 *
	 * The counterpart to `/link`: linking is the confident signal and merges on its own, this is the
	 * unconfident one and only ever files a queue item. Duplicates collapse, so a hundred users
	 * reporting the same bad merge is one thing for a moderator to look at (PLAN.md §2.7).
	 */
	post("/{id}/dispute") {
		val caller = call.requireCaller(identities)
		call.enforceLimit(limiter, RateLimiter.Bucket.GENERAL, caller.tier, caller.identity.id)

		val workId = call.parameters["id"]?.toLongOrNull() ?: throw ApiException(ApiError.BadRequest("id"))
		val body = call.receive<DisputeRequest>()
		if (body.kind !in DISPUTE_KINDS) throw ApiException(ApiError.BadRequest("kind"))

		queues?.fileDispute(
			workId = workId,
			otherWorkId = body.otherWorkId?.toLongOrNull(),
			kind = body.kind,
			reportedBy = caller.identity.id,
			note = body.note?.take(MAX_DISPUTE_NOTE),
		)
		// Always 202, whether or not it collapsed into an existing item: the user has done their part
		// and how the queue is shaped is none of their business.
		call.respond(HttpStatusCode.Accepted, LinkResponse("filed"))
	}
}

@Serializable
data class DisputeRequest(
	/** same_work - these two are one; different_works - this one has swallowed two. */
	val kind: String,
	@SerialName("other_work_id") val otherWorkId: String? = null,
	val note: String? = null,
)

private val DISPUTE_KINDS = setOf("same_work", "different_works")
private const val MAX_DISPUTE_NOTE = 500

private const val MAX_BATCH = 25
private const val MAX_SOURCE = 64
private const val MAX_SOURCE_KEY = 512
private const val MAX_TITLE = 500
private const val MAX_ALT_TITLES = 32
private const val MAX_TITLE_INPUTS_PER_BATCH = 100
private const val MAX_EXTERNAL_IDS = 10
private const val MAX_EXTERNAL_PROVIDER = 32
private const val MAX_EXTERNAL_ID = 128
private const val MAX_GENRE_LABELS = 200
private const val MAX_GENRE_LABEL = 60
private const val MAX_CONTENT_TYPE = 32
private const val MAX_EVIDENCE = 200
private val SOURCE_PATTERN = Regex("[A-Za-z0-9_.-]+")

/** A source and an entry on it, as every route that takes one accepts it. */
internal fun FingerprintRef.isValid(): Boolean =
	source.length in 1..MAX_SOURCE &&
		SOURCE_PATTERN.matches(source) &&
		key.length in 1..MAX_SOURCE_KEY &&
		key.none(Char::isISOControl)

private fun FingerprintDto.isValid(): Boolean =
	FingerprintRef(source, key).isValid() &&
		source !in WorkLimits.NON_NETWORK_SOURCES &&
		title.length in 1..MAX_TITLE &&
		title.none(Char::isISOControl) &&
		altTitles.size <= MAX_ALT_TITLES &&
		altTitles.all { it.length in 1..MAX_TITLE && it.none(Char::isISOControl) } &&
		WorkLimits.isValidYear(year) &&
		(contentType == null || contentType.length <= MAX_CONTENT_TYPE) &&
		externalIds.size <= MAX_EXTERNAL_IDS &&
		externalIds.all { (provider, id) ->
			provider.length in 1..MAX_EXTERNAL_PROVIDER && id.length in 1..MAX_EXTERNAL_ID
		} &&
		// Only absurd input is rejected. A manga carrying more labels than the server cares about is
		// ordinary - sources tag generously - and failing its fingerprint would cost the reader the
		// work's ratings and comments over a category nobody needs.
		genres.size <= MAX_GENRE_LABELS &&
		genres.all { it.length <= MAX_GENRE_LABEL }

private fun String.sanitizedEvidence(): String = map { if (it.isISOControl()) ' ' else it }
	.joinToString("")
	.trim()
