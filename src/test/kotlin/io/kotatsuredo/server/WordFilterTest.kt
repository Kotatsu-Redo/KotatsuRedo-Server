package io.kotatsuredo.server

import io.kotatsuredo.server.comments.ContentFilter
import io.kotatsuredo.server.filter.AhoCorasick
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.filter.FilterNormalizer
import io.kotatsuredo.server.filter.FilterRepository
import io.kotatsuredo.server.filter.FilterRules
import io.kotatsuredo.server.filter.FilterService
import io.kotatsuredo.server.filter.FilterTier
import io.kotatsuredo.server.filter.WordFilter
import io.kotatsuredo.server.works.WorkRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The filter, and specifically the false positives.
 *
 * Catching `fuck` is easy and no test would ever fail on it. What decides whether this feature feels
 * strict or feels broken is whether `assassin`, `Scunthorpe` and `therapist` get through, so that is
 * what most of this file is about.
 */
class WordFilterTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val repository by lazy { FilterRepository(source) }
	private val works by lazy { WorkRepository(source) }
	private val filter by lazy { WordFilter(repository) }
	private val service by lazy { FilterService(repository, filter) }
	private val identities by lazy {
		IdentityService(
			IdentityRepository(PostgresTestBase.database.exposed, PostgresTestBase.database.source),
			DevicePepper.of("test"),
		)
	}

	/** `filter_block.user_id` is a real foreign key, so a block has to belong to a real account. */
	private fun user(seed: String): String =
		(identities.hello(seed, DeviceIdentifiers("dev-$seed", null)) as HelloOutcome.Ok).identity.id

	private fun makeEstablished(userId: String) {
		source.connection.use { connection ->
			connection.autoCommit = false
			try {
				connection.prepareStatement(
					"UPDATE app_user SET created_at = now() - INTERVAL '91 days' WHERE id = ?",
				).use {
					it.setString(1, userId)
					it.executeUpdate()
				}
				connection.prepareStatement(
					"""
					INSERT INTO user_active_day (user_id, day)
					SELECT ?, CURRENT_DATE - n::int FROM generate_series(0, 29) AS n
					ON CONFLICT DO NOTHING
					""".trimIndent(),
				).use {
					it.setString(1, userId)
					it.executeUpdate()
				}
				connection.commit()
			} catch (error: Exception) {
				connection.rollback()
				throw error
			} finally {
				connection.autoCommit = true
			}
		}
	}

	private fun makeNormal(userId: String) {
		makeEstablished(userId)
		source.connection.use { connection ->
			connection.prepareStatement(
				"UPDATE app_user SET created_at = now() - INTERVAL '30 days' WHERE id = ?",
			).use {
				it.setString(1, userId)
				it.executeUpdate()
			}
		}
	}

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		source.connection.use { connection ->
			connection.createStatement().use {
				it.execute(
					"TRUNCATE filter_block, filter_rule, filter_allow, comment, comment_vote, work, " +
						"work_title, work_alias, work_cover_hash, work_external_id, work_relation, " +
						"work_merge_log, app_user, user_active_day, app_device, device_ban, " +
						"ban_evasion_flag, mod_action, mod_session, mod_totp_use, moderator CASCADE",
				)
			}
		}
		service.seed()
		service.reload()
	}

	private val context = ContentFilter.Context(userId = null, surface = "comment")

	private fun check(text: String, lang: String? = "en") = filter.check(text, lang, context)

	private fun blocked(text: String, lang: String? = "en") =
		assertIs<ContentFilter.Verdict.Blocked>(check(text, lang), "should have been blocked: $text")

	private fun allowed(text: String, lang: String? = "en") =
		assertEquals(ContentFilter.Verdict.Allowed, check(text, lang), "should have been allowed: $text")

	// -- the point of the whole exercise -----------------------------------------------------------

	@Test
	fun `the Scunthorpe class passes`() {
		// Each of these contains a listed term and is an ordinary word. Blocking any of them is the
		// failure mode that makes a filter feel broken rather than strict.
		listOf(
			"The assassin arc was the best part of this whole series.",
			"I grew up near Scunthorpe and this reminds me of it.",
			"His therapist would have a lot to say about that arc.",
			"The classroom scene was genuinely well drawn this week.",
			"That was a massive improvement over the last chapter.",
			"She is analysing the plot far more than the author did.",
			"The cocktail party chapter was pointless but pretty.",
			"I have a new appetite for this series after that ending.",
			"Nothing happened on Saturday and yet it was still good.",
			"He was embarrassed and the art sold it perfectly.",
		).forEach(::allowed)
	}

	@Test
	fun `a whole-token match blocks even though the dictionary knows the word`() {
		// The critical detail: spellcheck dictionaries contain profanity too, so "is it a word" alone
		// would allow everything. `token != match` is what makes the rule correct.
		val verdict = blocked("This chapter was absolute shit and I want my time back.")
		assertEquals("shit", verdict.term)
	}

	@Test
	fun `repeats and punctuation inside a word do not get past it`() {
		listOf(
			"That chapter was fuuuuck awful honestly",
			"That chapter was f.u.c.k awful honestly",
			"That chapter was f-u-c-k awful honestly",
			"That chapter was FUCK awful honestly",
			"That chapter was ｆｕｃｋ awful honestly",
			"That chapter was fu​ck awful honestly",
		).forEach { assertEquals("fuck", blocked(it).term, "missed: $it") }
	}

	@Test
	fun `the unambiguous substitutions are folded`() {
		assertEquals("shit", blocked("This chapter was total \$hit from start to finish").term)
		assertEquals("ass", blocked("Stop being such an @ss about the translation").term)
	}

	@Test
	fun `separator evasion is glued back together`() {
		// `f u c k` is four tokens and one word. The window is bounded to a few short tokens, which is
		// what keeps `the rap ist` in scope so the dictionary can rescue `therapist`.
		assertNotNull(filter.match("what the f u c k was that ending about", "en"))
		allowed("I told my the rap ist about this manga and they laughed")
	}

	@Test
	fun `an allowlisted word survives even a whole-token match`() {
		blocked("Stop being such an ass about the translation")

		repository.allow("ass", origin = "manual", note = null, moderatorId = null)
		service.reload()

		// A moderator's explicit decision beats the list, which is the entire point of the one-click
		// allowlist in the panel.
		allowed("Stop being such an ass about the translation")
	}

	@Test
	fun `a work title exempts its own words from profanity`() {
		blocked("Reading Bitch alone would not tell you much about it")

		val workId = works.createWork("Bitch", 2019, "manga", nsfw = false)
		works.addTitles(
			workId,
			listOf(io.kotatsuredo.server.works.TitleToStore("Bitch", "canonical")),
		)
		service.reload()

		// People discuss works with deliberately crude titles, and romanised Japanese collides with
		// profanity in several European languages.
		allowed("Reading Bitch alone would not tell you much about it")
	}

	// -- tiers ---------------------------------------------------------------------------------------

	@Test
	fun `severe is global and profanity is not`() {
		// A comment detected as French still meets the severe list, because that one applies
		// everywhere...
		assertNotNull(filter.match("quelle bande de nigger dans ce chapitre", "fr"))

		// ...while the English profanity list does not reach a language it was not written for. It is
		// still applied alongside the comment's own language, which is why this uses a French sentence
		// containing an English swear and expects the English list to catch it.
		assertNotNull(filter.match("ce chapitre etait vraiment shit", "fr"))

		// And a language *with* a list of its own is filtered by that list: `mierda` is Spanish and
		// appears in no other launch language's list.
		val spanish = assertNotNull(filter.match("Este capitulo fue una completa mierda", "es"))
		assertEquals("mierda", spanish.term)
		// The same word in a German comment is not filtered, which is the point of not unioning.
		allowed("Dieses Kapitel war mierda", "de")
	}

	@Test
	fun `a watch term publishes and flags rather than blocking`() {
		val verdict = check("This whole arc is completely overrated and I said what I said")
		val flagged = assertIs<ContentFilter.Verdict.Flagged>(verdict)
		assertEquals("overrated", flagged.term)
	}

	@Test
	fun `severe wins over profanity even when it appears later`() {
		// Severe is checked first on purpose: it is the tier where a miss is harmful, so it must not
		// be shadowed by a profanity match that happens to come earlier in the sentence.
		val hit = assertNotNull(filter.match("this shit chapter and its nigger dialogue", "en"))
		assertEquals(FilterTier.SEVERE, hit.tier)
	}

	// -- the feedback loop ---------------------------------------------------------------------------

	@Test
	fun `every block is logged with its rule and language`() {
		val userId = user("a")
		filter.check("This chapter was absolute shit", "en", ContentFilter.Context(userId, "comment"))

		val logged = repository.recentBlocks(10).single()
		assertEquals("shit", logged.term)
		assertEquals("en", logged.lang)
		assertEquals(userId, logged.userId)
		assertNotNull(logged.ruleId)
		assertContains(assertNotNull(logged.context), "absolute shit")
	}

	@Test
	fun `a rule disputed by distinct settled users demotes itself`() {
		val rule = repository.activeRules().first { it.term == "shit" }

		repeat(FilterRules.AUTO_DEMOTE_MIN_REPORTERS) { index ->
			val userId = user("u$index")
			makeEstablished(userId)
			val verdict = filter.check(
				"This chapter was absolute shit number $index",
				"en",
				ContentFilter.Context(userId, "comment"),
			)
			val blockId = assertNotNull(assertIs<ContentFilter.Verdict.Blocked>(verdict).blockId)
			repository.dispute(blockId, userId)
		}

		assertEquals(listOf(rule.id), service.demoteMisfiringRules())
		// And it stops blocking straight away, rather than at the next deploy.
		allowed("This chapter was absolute shit")
	}

	@Test
	fun `normal accounts cannot auto-demote a rule`() {
		val rule = repository.activeRules().first { it.term == "shit" }
		repeat(FilterRules.AUTO_DEMOTE_MIN_REPORTERS) { index ->
			val userId = user("new-$index")
			makeNormal(userId)
			val verdict = filter.check(
				"This chapter was absolute shit number $index",
				"en",
				ContentFilter.Context(userId, "comment"),
			)
			val blockId = assertNotNull(assertIs<ContentFilter.Verdict.Blocked>(verdict).blockId)
			assertTrue(repository.dispute(blockId, userId))
		}

		assertTrue(service.demoteMisfiringRules().isEmpty())
		assertNull(repository.rule(rule.id)?.demotedAt)
	}

	@Test
	fun `severe rules are never auto-demoted`() {
		val rule = repository.activeRules().first { it.tier == FilterTier.SEVERE }
		repeat(FilterRules.AUTO_DEMOTE_MIN_REPORTERS) { index ->
			val userId = user("severe-$index")
			makeEstablished(userId)
			val blockId = repository.recordBlock(
				rule.id, rule.term, rule.tier, rule.lang, "comment", userId, rule.termRaw,
			)
			assertTrue(repository.dispute(blockId, userId))
		}

		assertTrue(service.demoteMisfiringRules().isEmpty())
		assertNull(repository.rule(rule.id)?.demotedAt)
	}

	@Test
	fun `a user can dispute their own block once and nobody else's`() {
		val mine = user("mine")
		val verdict = filter.check("absolute shit chapter", "en", ContentFilter.Context(mine, "comment"))
		val blockId = assertNotNull(assertIs<ContentFilter.Verdict.Blocked>(verdict).blockId)

		assertTrue(repository.dispute(blockId, mine))
		// Twice would let one person demote a rule on their own.
		assertFalse(repository.dispute(blockId, mine))
		assertFalse(repository.dispute(blockId, user("someone-else")))
	}

	@Test
	fun `one user gets only one dispute signal per rule`() {
		val mine = user("repeat")
		val first = filter.check("absolute shit chapter one", "en", ContentFilter.Context(mine, "comment"))
		val second = filter.check("absolute shit chapter two", "en", ContentFilter.Context(mine, "comment"))
		val firstId = assertNotNull(assertIs<ContentFilter.Verdict.Blocked>(first).blockId)
		val secondId = assertNotNull(assertIs<ContentFilter.Verdict.Blocked>(second).blockId)

		assertTrue(repository.dispute(firstId, mine))
		assertFalse(repository.dispute(secondId, mine))
	}

	@Test
	fun `the retained text is forgotten but the counts survive`() {
		filter.check("absolute shit chapter", "en", ContentFilter.Context(user("a"), "comment"))
		assertNotNull(repository.recentBlocks(1).single().context)

		// Pretend the review window passed.
		source.connection.use { connection ->
			connection.createStatement().use {
				it.execute("UPDATE filter_block SET created_at = now() - INTERVAL '90 days'")
			}
		}
		assertEquals(1, service.purgeOldContext())

		val logged = repository.recentBlocks(1).single()
		assertNull(logged.context, "the blocked text should have been forgotten")
		assertEquals("shit", logged.term, "but the row and its counts stay - they tune the lists")
	}

	@Test
	fun `per-language rates are visible, because an outlier means a bad list`() {
		filter.check("absolute shit", "en", ContentFilter.Context(user("a"), "comment"))
		filter.check("nigger", "fr", ContentFilter.Context(user("b"), "comment"))

		val byLanguage = repository.languageStats().associate { it.lang to it.blocks }
		assertEquals(1, byLanguage["en"])
		assertEquals(1, byLanguage["fr"])
	}

	// -- seeding ---------------------------------------------------------------------------------------

	@Test
	fun `seeding is idempotent and never undoes a moderator`() {
		val before = repository.activeRules().size
		assertEquals(0, service.seed(), "a second seed should add nothing")

		val rule = repository.activeRules().first { it.term == "shit" }
		repository.setRuleEnabled(rule.id, false)
		service.seed()

		assertTrue(repository.activeRules().none { it.term == "shit" }, "the seed re-enabled a disabled rule")
		assertEquals(before - 1, repository.activeRules().size)
	}

	// -- the automaton ---------------------------------------------------------------------------------

	@Test
	fun `the automaton finds overlapping and suffix matches`() {
		val automaton = AhoCorasick.build(listOf("he", "she", "his", "hers"))
		val terms = automaton.findAll("ushers").map { it.term }.toSet()
		// `she` and `he` both end inside `ushers`; missing the suffix link would lose `he`.
		assertEquals(setOf("she", "he", "hers"), terms)
	}

	@Test
	fun `a short severe match cannot mask a later eligible severe match`() {
		repository.addRule("ab", "ab", FilterTier.SEVERE, null, "test")
		repository.addRule("cdefg", "cdefg", FilterTier.SEVERE, null, "test")
		filter.reload()

		val hit = filter.match("abcdefgh", "en")

		assertEquals("cdefg", hit?.term)
		assertEquals(FilterTier.SEVERE, hit?.tier)
	}

	@Test
	fun `an empty automaton matches nothing rather than everything`() {
		val automaton = AhoCorasick.build(emptyList())
		assertTrue(automaton.isEmpty)
		assertNull(automaton.findFirst("anything at all"))
	}

	// -- normalisation ---------------------------------------------------------------------------------

	@Test
	fun `set B folds the digits set A leaves alone`() {
		assertEquals("shit", FilterNormalizer.setB("5h1t"))
		// Set A deliberately does not: `1` is `i` or `l` and `5` is `s` only sometimes, and folding
		// them turns ordinary strings into matches.
		assertEquals("5h1t", FilterNormalizer.setA("5h1t"))
	}

	@Test
	fun `homoglyphs and diacritics fold, doubles do not`() {
		assertEquals("fuck", FilterNormalizer.setA("fuсk"), "Cyrillic es should fold to Latin c")
		assertEquals("naive", FilterNormalizer.setA("naïve"))
		assertEquals("bass", FilterNormalizer.setA("bass"), "an ordinary double must survive")
		assertEquals("bass", FilterNormalizer.setA("baaass"), "a long run folds to one, a double stays")
		assertEquals("fuck", FilterNormalizer.setA("fuuuck"), "which is the case the lists depend on")
	}
}
