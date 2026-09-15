package io.kotatsuredo.server.routes

import io.kotatsuredo.server.ApiError
import io.kotatsuredo.server.ApiException
import io.kotatsuredo.server.filter.FilterBlockRecord
import io.kotatsuredo.server.filter.FilterNormalizer
import io.kotatsuredo.server.filter.FilterRepository
import io.kotatsuredo.server.filter.FilterRule
import io.kotatsuredo.server.filter.FilterRules
import io.kotatsuredo.server.filter.FilterService
import io.kotatsuredo.server.filter.FilterTier
import io.kotatsuredo.server.filter.LanguageStats
import io.kotatsuredo.server.filter.RuleStats
import io.kotatsuredo.server.moderation.ModerationRules
import io.kotatsuredo.server.moderation.ModerationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

@Serializable
data class FilterBlockDto(
	val id: String,
	@SerialName("rule_id") val ruleId: String? = null,
	val term: String,
	val tier: String,
	val lang: String? = null,
	val surface: String,
	@SerialName("user_id") val userId: String? = null,
	val user: String? = null,
	/** Null once the review window has passed and the text was forgotten. */
	val context: String? = null,
	@SerialName("created_at") val createdAt: String,
	/** The user pressed "this was wrong". These sort first. */
	val disputed: Boolean = false,
	val resolution: String? = null,
)

@Serializable
data class FilterRuleDto(
	val id: String,
	val term: String,
	@SerialName("term_raw") val termRaw: String,
	val tier: String,
	val lang: String? = null,
	val enabled: Boolean,
	/** True when the rule turned itself off after too many confirmed false positives. */
	val demoted: Boolean,
	val source: String,
)

@Serializable
data class RuleStatsDto(
	@SerialName("rule_id") val ruleId: String? = null,
	val term: String,
	val tier: String,
	val lang: String? = null,
	val blocks: Int,
	val disputed: Int,
	@SerialName("false_positive_rate") val falsePositiveRate: Double,
)

@Serializable
data class LanguageStatsDto(
	val lang: String? = null,
	val blocks: Int,
	val disputed: Int,
)

@Serializable
data class AddRuleRequest(
	val term: String,
	val tier: String,
	val lang: String? = null,
)

@Serializable
data class AllowRequest(val term: String, val note: String? = null)

private fun FilterBlockRecord.toDto() = FilterBlockDto(
	id = id.toString(),
	ruleId = ruleId?.toString(),
	term = term,
	tier = tier.name.lowercase(),
	lang = lang,
	surface = surface,
	userId = userId,
	user = userName,
	context = context,
	createdAt = DateTimeFormatter.ISO_INSTANT.format(createdAt.toInstant()),
	disputed = disputedAt != null,
	resolution = resolution,
)

private fun FilterRule.toDto() = FilterRuleDto(
	id = id.toString(),
	term = term,
	termRaw = termRaw,
	tier = tier.name.lowercase(),
	lang = lang,
	enabled = isEnabled,
	demoted = demotedAt != null,
	source = source,
)

private fun RuleStats.toDto() = RuleStatsDto(
	ruleId = ruleId?.toString(),
	term = term,
	tier = tier.name.lowercase(),
	lang = lang,
	blocks = blocks,
	disputed = disputed,
	falsePositiveRate = falsePositiveRate,
)

private fun LanguageStats.toDto() = LanguageStatsDto(lang, blocks, disputed)

/**
 * The filter's half of the panel.
 *
 * This is the feedback loop, and it matters more than any rule in the lists: a moderator who sees
 * `assassin` blocked has to be able to fix it permanently in one action, and a language whose block
 * rate is an outlier has to be visible before it quietly ruins the feature for those users
 * (PLAN.md §6).
 */
fun Route.filterAdminRoutes(
	moderation: ModerationService,
	filters: FilterRepository,
	service: FilterService,
) = route("/admin/api/filter") {

	get("/blocks") {
		call.requireModerator(moderation)
		val all = call.request.queryParameters["all"] == "true"
		val limit = call.filterPageSize()
		call.respond((if (all) filters.recentBlocks(limit) else filters.openBlocks(limit)).map { it.toDto() })
	}

	get("/rules") {
		call.requireModerator(moderation)
		call.respond(filters.allRules(MAX_RULES).map { it.toDto() })
	}

	/** Per-rule false-positive rates, worst first: what to read before touching a list by hand. */
	get("/stats/rules") {
		call.requireModerator(moderation)
		call.respond(filters.ruleStats(MIN_STATS_BLOCKS).map { it.toDto() })
	}

	/** An outlier here means a bad list, not a rude userbase. */
	get("/stats/languages") {
		call.requireModerator(moderation)
		call.respond(filters.languageStats().map { it.toDto() })
	}

	/**
	 * One click, and `assassin` is postable for good.
	 *
	 * The single most valuable action in the panel: it is what turns the word list from a guess made
	 * once into something tuned from real data in the first few weeks.
	 */
	post("/blocks/{id}/allow") {
		val session = call.requireModerator(moderation)
		val block = filters.block(call.filterId()) ?: throw ApiException(ApiError.NotFound)

		// The token the user actually typed, not the rule's term: allowlisting `ass` would switch the
		// rule off entirely, whereas allowlisting `assassin` fixes exactly the word that was wrong.
		val term = call.receive<AllowRequest>().term.trim().let(FilterNormalizer::setA)
		if (term.isEmpty() || term.length > MAX_TERM_LENGTH) throw ApiException(ApiError.BadRequest("term"))

		filters.allow(term, origin = "manual", note = block.term, moderatorId = session.moderator.id)
		filters.resolveBlock(block.id, FilterRules.RESOLUTION_ALLOWLISTED)
		service.reload()

		moderation.recordFilterAction(session.moderator, "allowlist_term", term, block.term)
		call.respond(OkResponse())
	}

	post("/blocks/{id}/uphold") {
		call.requireModerator(moderation)
		if (!filters.resolveBlock(call.filterId(), FilterRules.RESOLUTION_UPHELD)) {
			throw ApiException(ApiError.NotFound)
		}
		call.respond(OkResponse())
	}

	post("/rules/{id}/demote") {
		val session = call.requireModerator(moderation)
		val id = call.filterId()
		val rule = filters.rule(id) ?: throw ApiException(ApiError.NotFound)
		if (!filters.demoteRule(id)) throw ApiException(ApiError.NotFound)
		service.reload()
		moderation.recordFilterAction(session.moderator, "demote_rule", rule.term, rule.tier.name.lowercase())
		call.respond(OkResponse())
	}

	post("/rules/{id}/restore") {
		val session = call.requireModerator(moderation)
		val id = call.filterId()
		val rule = filters.rule(id) ?: throw ApiException(ApiError.NotFound)
		if (!filters.restoreRule(id)) throw ApiException(ApiError.NotFound)
		service.reload()
		moderation.recordFilterAction(session.moderator, "restore_rule", rule.term, rule.tier.name.lowercase())
		call.respond(OkResponse())
	}

	post("/rules") {
		val session = call.requireModerator(moderation)
		val body = call.receive<AddRuleRequest>()
		val tier = FilterTier.parse(body.tier) ?: throw ApiException(ApiError.BadRequest("tier"))
		val raw = body.term.trim()
		if (raw.isEmpty() || raw.length > MAX_TERM_LENGTH) throw ApiException(ApiError.BadRequest("term"))

		// Normalised with the tier's own set and the rule's own language, because that is the space
		// the matcher works in - and for Vietnamese, Turkish and Polish that includes the marks.
		val lang = body.lang?.takeIf { it.isNotBlank() }
		if (lang != null && (lang.length > MAX_LANGUAGE_LENGTH || !LANGUAGE_PATTERN.matches(lang))) {
			throw ApiException(ApiError.BadRequest("lang"))
		}
		val term = if (tier == FilterTier.SEVERE) FilterNormalizer.setB(raw) else FilterNormalizer.setA(raw, lang)
		// Length checked on the folded form, because folding can shorten it: `xxx` becomes `x`.
		if (term.length < FilterRules.MIN_TERM_LENGTH) throw ApiException(ApiError.BadRequest("term"))
		// Only `severe` is ever global; everything else must name a language, or it would be applied
		// to comments written in languages where it means nothing.
		if (lang == null && tier != FilterTier.SEVERE) throw ApiException(ApiError.BadRequest("lang"))

		filters.addRule(term, raw, tier, lang, source = "panel")
		service.reload()
		moderation.recordFilterAction(session.moderator, "add_rule", term, tier.name.lowercase())
		call.respond(HttpStatusCode.Created, OkResponse())
	}
}

private const val MAX_RULES = 2000
private const val MIN_STATS_BLOCKS = 1
private const val MAX_TERM_LENGTH = 128
private const val MAX_LANGUAGE_LENGTH = 16
private val LANGUAGE_PATTERN = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})?")

private fun ApplicationCall.filterId(): Long =
	parameters["id"]?.toLongOrNull() ?: throw ApiException(ApiError.BadRequest("id"))

private fun ApplicationCall.filterPageSize(): Int =
	(request.queryParameters["limit"]?.toIntOrNull() ?: ModerationRules.DEFAULT_PAGE_SIZE)
		.coerceIn(1, ModerationRules.MAX_PAGE_SIZE)

/** Filter tuning is day-to-day moderation, not an admin power - a moderator has to be able to do it. */
private fun ApplicationCall.requireModerator(moderation: ModerationService) = requireEnrolled(moderation)
