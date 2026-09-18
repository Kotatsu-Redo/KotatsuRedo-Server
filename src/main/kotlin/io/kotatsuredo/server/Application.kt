package io.kotatsuredo.server

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.db.Database
import io.kotatsuredo.server.db.HealthProbe
import io.kotatsuredo.server.db.migrate
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.auth.configureTrustedProxyHeaders
import io.kotatsuredo.server.plugins.configureLogging
import io.kotatsuredo.server.plugins.configureAdminCsrf
import io.kotatsuredo.server.plugins.configureBlockingCalls
import io.kotatsuredo.server.plugins.configurePanelHeaders
import io.kotatsuredo.server.plugins.configurePreAuthRateLimit
import io.kotatsuredo.server.plugins.configureSerialization
import io.kotatsuredo.server.plugins.configureStatusPages
import io.kotatsuredo.server.routes.healthRoutes
import io.kotatsuredo.server.routes.exportRoutes
import io.kotatsuredo.server.routes.identityRoutes
import io.kotatsuredo.server.ratings.RatingRepository
import io.kotatsuredo.server.ratings.RatingService
import io.kotatsuredo.server.routes.ratingRoutes
import io.kotatsuredo.server.routes.scoreRoutes
import io.kotatsuredo.server.routes.telemetryRoutes
import io.kotatsuredo.server.routes.workRoutes
import io.kotatsuredo.server.scoring.ScoringRepository
import io.kotatsuredo.server.scoring.ScoringService
import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.filter.FilterRepository
import io.kotatsuredo.server.filter.FilterService
import io.kotatsuredo.server.filter.StopwordLanguageDetector
import io.kotatsuredo.server.filter.WordFilter
import io.kotatsuredo.server.moderation.ModerationQueueRepository
import io.kotatsuredo.server.moderation.ModerationService
import io.kotatsuredo.server.moderation.OverviewRepository
import io.kotatsuredo.server.ops.OpsRepository
import io.kotatsuredo.server.moderation.ModeratorRepository
import io.kotatsuredo.server.moderation.TotpSecretCipher
import io.kotatsuredo.server.routes.adminRoutes
import io.kotatsuredo.server.routes.filterAdminRoutes
import io.kotatsuredo.server.routes.rulesRoute
import io.ktor.server.http.content.staticResources
import io.kotatsuredo.server.comments.CommentService
import io.kotatsuredo.server.routes.commentRoutes
import io.kotatsuredo.server.catalogue.CatalogueLookup
import io.kotatsuredo.server.catalogue.JdkHttpFetcher
import io.kotatsuredo.server.catalogue.KitsuCatalogue
import io.kotatsuredo.server.catalogue.MangaDexCatalogue
import io.kotatsuredo.server.catalogue.MangaUpdatesCatalogue
import io.kotatsuredo.server.telemetry.TelemetryRepository
import io.kotatsuredo.server.telemetry.TelemetryRetention
import io.kotatsuredo.server.telemetry.TelemetryService
import io.kotatsuredo.server.works.WorkDeduplicator
import io.kotatsuredo.server.works.WorkEnricher
import io.kotatsuredo.server.works.WorkLinker
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkResolver
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val SERVER_VERSION = "0.1.0"

fun main(args: Array<String>) {
	if (args.contentEquals(arrayOf("migrate"))) {
		Database.connect(Config.migrationDatabaseFromEnv(), enforceQueryTimeouts = false).use { migrations ->
			migrations.source.migrate()
		}
		return
	}
	require(args.isEmpty()) { "Unknown command. Use no arguments to serve, or 'migrate' to migrate the database." }

	val config = Config.fromEnv()
	val database = Database.connect(config.database)

	// Built before identity, because nicknames run through the same filter as comments.
	val filterRepository = FilterRepository(database.source)
	val wordFilter = WordFilter(filterRepository)
	val filters = FilterService(filterRepository, wordFilter)
	filters.seed()
	filters.reload()

	val commentRepository = CommentRepository(database.source)
	val ratings = RatingService(RatingRepository(database.source))
	val identities = IdentityService(
		repository = IdentityRepository(database.exposed, database.source),
		pepper = DevicePepper.of(config.devicePepper),
		filter = wordFilter,
	)
	val telemetryRepository = TelemetryRepository(database.source)
	val telemetry = TelemetryService(telemetryRepository)
	val retention = TelemetryRetention(telemetryRepository)
	val scoring = ScoringService(ScoringRepository(database.source))

	// Kitsu first, MangaUpdates as fallback. Consulted only when a work is genuinely unknown; see
	// PLAN.md §2.3 for why there is no bulk seed.
	val fetcher = JdkHttpFetcher()
	val workRepository = WorkRepository(database.source)
	// MangaDex first: one request returns a work's MyAnimeList, AniList, Kitsu and MangaUpdates ids,
	// and those ids are what let duplicates found by different sources turn out to be one work.
	val catalogue = CatalogueLookup(
		listOf(MangaDexCatalogue(fetcher), KitsuCatalogue(fetcher), MangaUpdatesCatalogue(fetcher)),
	)
	val resolver = WorkResolver(repository = workRepository)
	val queues = ModerationQueueRepository(database.source)
	// A pair the resolver refuses to merge is filed for a human instead of dropped (§2.7).
	val linker = WorkLinker(workRepository) { a, b, evidence ->
		queues.fileDispute(a, b, kind = "needs_review", reportedBy = null, note = evidence)
	}
	// The word filter stays at its default until the launch word lists are curated (PLAN.md §6);
	// swapping it in is this one argument.
	val comments = CommentService(
		repository = commentRepository,
		filter = wordFilter,
		detector = StopwordLanguageDetector(),
		minLength = config.commentMinLength,
	)
	val moderatorRepository = ModeratorRepository(
		database.source,
		TotpSecretCipher.fromEncoded(config.totpEncryptionKey),
	)
	moderatorRepository.encryptLegacyTotpSecrets()
	val moderation = ModerationService(
		moderators = moderatorRepository,
		queues = queues,
		comments = commentRepository,
		identities = IdentityRepository(database.exposed, database.source),
		works = workRepository,
		ratings = ratings,
	)
	config.bootstrapModerator?.let { (username, password) ->
		moderation.bootstrapFirstAdmin(username, password)
	}
	val limiter = RateLimiter()

	// Retention is a promise in the privacy notice, so it runs for as long as the server does.
	// Scoring runs on the same scope: raw rows are purged at 7 days, so if this stopped, scores would
	// silently freeze at their last value rather than obviously breaking.
	val jobs = CoroutineScope(SupervisorJob())
	retention.schedule(jobs)
	scoring.schedule(jobs)
	// Forgets the text behind old blocks, and demotes rules that keep being wrong.
	filters.schedule(jobs)
	// Merges works that were split across sources, so nobody has to find them by hand.
	WorkDeduplicator(workRepository, ratings).schedule(jobs)
	// Describes newly seen works after the fact, so no reader waits on Kitsu or MangaUpdates.
	WorkEnricher(workRepository, catalogue, ratings).schedule(jobs)
	jobs.launch(Dispatchers.IO) {
		while (isActive) {
			delay(60 * 60 * 1000L)
			limiter.evictExpired()
			runCatching { moderation.sweep() }
				.onFailure { org.slf4j.LoggerFactory.getLogger("Housekeeping").warn("Housekeeping failed", it) }
		}
	}

	Runtime.getRuntime().addShutdownHook(Thread {
		jobs.cancel()
		database.close()
	})

	embeddedServer(Netty, port = config.port, host = config.host) {
		module(
			health = database,
			identities = identities,
			telemetry = telemetry,
			scoring = scoring,
			resolver = resolver,
			linker = linker,
			ratings = ratings,
			comments = comments,
			commentsRepository = commentRepository,
			works = workRepository,
			moderation = moderation,
			queues = queues,
			overviews = OverviewRepository(database.source),
			ops = OpsRepository(database.source),
			filters = filterRepository,
			filterService = filters,
			limiter = limiter,
			trustProxyHeaders = config.trustProxyHeaders,
			blockingParallelism = config.database.maxPoolSize,
			rulesUrl = config.rulesUrl,
			// Without TLS the session cookie would never be sent, and the panel could not log in at
			// all on a local HTTP run.
			secureCookies = config.secureCookies,
		)
	}.start(wait = true)
}

/**
 * Wired by [main] in production and by `testApplication` in tests, so both exercise the same plugin
 * stack - in particular the same logging configuration.
 */
fun Application.module(
	health: HealthProbe,
	identities: IdentityService? = null,
	telemetry: TelemetryService? = null,
	scoring: ScoringService? = null,
	resolver: WorkResolver? = null,
	linker: WorkLinker? = null,
	ratings: RatingService? = null,
	comments: CommentService? = null,
	commentsRepository: CommentRepository? = null,
	works: WorkRepository? = null,
	moderation: ModerationService? = null,
	queues: ModerationQueueRepository? = null,
	filters: FilterRepository? = null,
	filterService: FilterService? = null,
	limiter: RateLimiter = RateLimiter(),
	trustProxyHeaders: Boolean = false,
	blockingParallelism: Int = 16,
	overviews: OverviewRepository? = null,
	ops: OpsRepository? = null,
	rulesUrl: String = "/rules",
	secureCookies: Boolean = true,
) {
	configureBlockingCalls(blockingParallelism)
	configureLogging()
	configureSerialization()
	configureStatusPages()
	configureTrustedProxyHeaders(trustProxyHeaders)
	configurePreAuthRateLimit(limiter)
	configurePanelHeaders()
	configureAdminCsrf()
	limiter.validate(org.slf4j.LoggerFactory.getLogger("RateLimiter"))

	routing {
		route("/v1") {
			healthRoutes(health, SERVER_VERSION)
			// Source health is non-personal aggregate data and intentionally CDN-cacheable.
			if (scoring != null) {
				scoreRoutes(scoring)
			}
			if (identities != null) {
				identityRoutes(identities, limiter, rulesUrl)
				if (telemetry != null) {
					telemetryRoutes(identities, telemetry, limiter)
				}
				if (resolver != null && linker != null) {
					workRoutes(identities, resolver, linker, limiter, queues)
				}
				if (ratings != null && works != null) {
					ratingRoutes(identities, ratings, works, limiter)
				}
				if (ratings != null && commentsRepository != null) {
					// "Give me a copy of everything you hold about me", self-service: the key is the
					// only proof of identity this server has, and it is the same proof the delete
					// button already accepts.
					exportRoutes(identities, commentsRepository, ratings, limiter)
				}
				if (comments != null && works != null) {
					commentRoutes(identities, comments, works, limiter, rulesUrl, filters)
				}
			}
		}

		// The rules the rejection dialog links to, served by the instance so the link is versioned
		// with the deployment and does not depend on GitHub being reachable (PLAN.md §6).
		rulesRoute()

		// Outside /v1 deliberately: a different audience, a different auth model, and nothing an app
		// client holds should be able to reach it.
		if (moderation != null && queues != null) {
			adminRoutes(moderation, queues, secureCookies, overviews, ops, works, limiter)
			if (filters != null && filterService != null) {
				filterAdminRoutes(moderation, filters, filterService)
			}
			// The panel itself, from the same container - no second deployment (PLAN.md §6).
			staticResources("/admin", "static/admin", index = "index.html")
		}
	}
}
