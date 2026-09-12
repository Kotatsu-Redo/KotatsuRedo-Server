package io.kotatsuredo.server

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.db.Database
import io.kotatsuredo.server.db.HealthProbe
import io.kotatsuredo.server.db.migrate
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.plugins.configureLogging
import io.kotatsuredo.server.plugins.configurePanelHeaders
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
import io.kotatsuredo.server.moderation.ModeratorRepository
import io.kotatsuredo.server.routes.adminRoutes
import io.kotatsuredo.server.routes.filterAdminRoutes
import io.kotatsuredo.server.routes.rulesRoute
import io.ktor.server.http.content.staticResources
import io.kotatsuredo.server.comments.CommentService
import io.kotatsuredo.server.routes.commentRoutes
import io.kotatsuredo.server.catalogue.CatalogueLookup
import io.kotatsuredo.server.catalogue.JdkHttpFetcher
import io.kotatsuredo.server.catalogue.KitsuCatalogue
import io.kotatsuredo.server.catalogue.MangaUpdatesCatalogue
import io.kotatsuredo.server.telemetry.TelemetryRepository
import io.kotatsuredo.server.telemetry.TelemetryRetention
import io.kotatsuredo.server.telemetry.TelemetryService
import io.kotatsuredo.server.works.WorkLinker
import io.kotatsuredo.server.works.WorkRepository
import io.kotatsuredo.server.works.WorkResolver
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

const val SERVER_VERSION = "0.1.0"

fun main() {
	val config = Config.fromEnv()
	val database = Database.connect(config.database)
	database.source.migrate()

	// Built before identity, because nicknames run through the same filter as comments.
	val filterRepository = FilterRepository(database.source)
	val wordFilter = WordFilter(filterRepository)
	val filters = FilterService(filterRepository, wordFilter)
	filters.seed()
	filters.reload()

	val commentRepository = CommentRepository(database.source)
	val ratings = RatingService(RatingRepository(database.source))
	val identities = IdentityService(
		repository = IdentityRepository(database.exposed),
		pepper = DevicePepper.of(config.devicePepper),
		filter = wordFilter,
		// Both are what make "delete everything about me" true rather than approximate: without them
		// a deletion takes other people's replies with it and leaves the ratings it erased counted.
		comments = commentRepository,
		ratings = ratings,
	)
	val telemetryRepository = TelemetryRepository(database.source)
	val telemetry = TelemetryService(telemetryRepository)
	val retention = TelemetryRetention(telemetryRepository)
	val scoring = ScoringService(ScoringRepository(database.source))

	// Kitsu first, MangaUpdates as fallback. Consulted only when a work is genuinely unknown; see
	// PLAN.md §2.3 for why there is no bulk seed.
	val fetcher = JdkHttpFetcher()
	val workRepository = WorkRepository(database.source)
	val resolver = WorkResolver(
		repository = workRepository,
		catalogue = CatalogueLookup(listOf(KitsuCatalogue(fetcher), MangaUpdatesCatalogue(fetcher))),
	)
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
	val moderation = ModerationService(
		moderators = ModeratorRepository(database.source),
		queues = queues,
		comments = commentRepository,
		identities = IdentityRepository(database.exposed),
		works = workRepository,
		ratings = ratings,
	)
	config.bootstrapModerator?.let { (username, password) ->
		moderation.bootstrapFirstAdmin(username, password)
	}

	// Retention is a promise in the privacy notice, so it runs for as long as the server does.
	// Scoring runs on the same scope: raw rows are purged at 7 days, so if this stopped, scores would
	// silently freeze at their last value rather than obviously breaking.
	val jobs = CoroutineScope(SupervisorJob())
	retention.schedule(jobs)
	scoring.schedule(jobs)
	// Forgets the text behind old blocks, and demotes rules that keep being wrong.
	filters.schedule(jobs)

	Runtime.getRuntime().addShutdownHook(Thread(database::close))

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
			filters = filterRepository,
			filterService = filters,
			rulesUrl = config.rulesUrl,
			// Without TLS the session cookie would never be sent, and the panel could not log in at
			// all on a local HTTP run.
			secureCookies = config.isProduction,
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
	rulesUrl: String = "/rules",
	secureCookies: Boolean = true,
) {
	configureLogging()
	configureSerialization()
	configureStatusPages()
	configurePanelHeaders()

	routing {
		route("/v1") {
			healthRoutes(health, SERVER_VERSION)
			if (identities != null) {
				identityRoutes(identities, limiter, rulesUrl)
				if (telemetry != null) {
					telemetryRoutes(identities, telemetry, limiter)
				}
				if (scoring != null) {
					scoreRoutes(identities, scoring, limiter)
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
			adminRoutes(moderation, queues, secureCookies)
			if (filters != null && filterService != null) {
				filterAdminRoutes(moderation, filters, filterService)
			}
			// The panel itself, from the same container - no second deployment (PLAN.md §6).
			staticResources("/admin", "static/admin", index = "index.html")
		}
	}
}
