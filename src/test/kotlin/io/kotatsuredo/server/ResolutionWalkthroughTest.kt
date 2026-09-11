package io.kotatsuredo.server

import io.kotatsuredo.server.catalogue.HttpFetcher
import io.kotatsuredo.server.catalogue.KitsuCatalogue
import io.kotatsuredo.server.works.TitleNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executable documentation for how one work ends up with one thread across many sources.
 *
 * The short version: a source calling a manga something different is usually not a matching problem
 * at all, because the catalogue already knows that rendering and the work is indexed under every one
 * of them. Fuzzy matching is the fallback for the rest, not the mechanism.
 */
class ResolutionWalkthroughTest {

	private fun fixture(name: String) =
		requireNotNull(javaClass.getResourceAsStream("/catalogue/$name.json")).bufferedReader().readText()

	private val record by lazy {
		requireNotNull(
			KitsuCatalogue(HttpFetcher { _, _ -> null }).parse(fixture("kitsu-chainsaw-man"), "Chainsaw Man"),
		)
	}

	/** What `work_title` holds after the work is created: every rendering, under every key it yields. */
	private val keyIndex: Map<String, String> by lazy {
		buildMap {
			record.titles.forEach { title ->
				TitleNormalizer.keys(title).forEach { key -> put(key, title) }
			}
		}
	}

	private fun resolve(sourceTitle: String): String? =
		TitleNormalizer.keys(sourceTitle).firstNotNullOfOrNull { keyIndex[it] }

	@Test
	fun `walk through resolving one work from many different source titles`() {
		println(
			buildString {
				appendLine()
				appendLine("=== How a work gets one thread across sources ===")
				appendLine()
				appendLine("1. First user opens comments on a manga nobody has resolved before.")
				appendLine("   The server misses on alias, external id and title, so it asks the catalogue once.")
				appendLine("   Kitsu returns ${record.titles.size} renderings and ${record.externalIds.size} external ids:")
				record.titles.take(8).forEach { appendLine("      $it") }
				if (record.titles.size > 8) appendLine("      ... and ${record.titles.size - 8} more")
				appendLine()
				appendLine("2. The work is stored indexed under EVERY rendering, ${keyIndex.size} normalized keys in all.")
				appendLine("   That is the whole trick: the work knows what other sources will call it")
				appendLine("   before those sources are ever seen.")
				appendLine()
				appendLine("3. Other sources now resolve with an exact key hit - no fuzzy matching involved:")
				listOf(
					"Chainsaw Man" to "an English source",
					"Chainsawman" to "a source that drops the space",
					"チェンソーマン" to "a Japanese source",
					"Человек-бензопила" to "a Russian source",
					"체인쏘맨" to "a Korean source",
					"CSM" to "a source using the abbreviation",
				).forEach { (title, who) ->
					val hit = resolve(title)
					appendLine("      ${if (hit != null) "MATCH  " else "miss   "} $title  ($who)")
				}
				appendLine()
				appendLine("4. A rendering nobody knows yet - say a scanlation site's own spelling - falls")
				appendLine("   through to trigram similarity and cover hashing (M2b). On success the new")
				appendLine("   rendering is written back as source_observed, so the NEXT user of that source")
				appendLine("   gets an exact hit. The catalogue improves with use.")
				appendLine()
				appendLine("5. Sequence markers are checked separately and always block a merge:")
				listOf("Chainsaw Man", "Chainsaw Man Part 2").forEach {
					appendLine("      '$it' -> sequence signature '${TitleNormalizer.sequenceSignature(it)}'")
				}
			},
		)
	}

	@Test
	fun `sources calling it different things all reach the same work`() {
		listOf("Chainsaw Man", "Chainsawman", "チェンソーマン", "Человек-бензопила", "체인쏘맨")
			.forEach { title ->
				assertTrue(resolve(title) != null, "'$title' did not resolve; index has ${keyIndex.size} keys")
			}
	}

	/** Punctuation and spacing differences between sources must not create a second work. */
	@Test
	fun `spelling and spacing variants land on the same work`() {
		assertTrue(resolve("Chain Saw Man") != null)
		assertTrue(resolve("chainsaw man") != null)
		assertTrue(resolve("CHAINSAW MAN") != null)
	}

	/** The guard that matters more than any match: a sequel is a different story. */
	@Test
	fun `a sequel does not inherit the thread`() {
		assertEquals("", TitleNormalizer.sequenceSignature("Chainsaw Man"))
		assertEquals("part 2", TitleNormalizer.sequenceSignature("Chainsaw Man Part 2"))
		assertTrue(
			!TitleNormalizer.differsOnlyByEdition("Chainsaw Man", "Chainsaw Man Part 2"),
			"a sequel must never merge into the original",
		)
	}

	/** An unrelated manga must not collide, however many keys the work is indexed under. */
	@Test
	fun `an unrelated work does not resolve here`() {
		listOf("Berserk", "One Piece", "Solo Leveling").forEach {
			assertEquals(null, resolve(it), "'$it' wrongly resolved to Chainsaw Man")
		}
	}
}
