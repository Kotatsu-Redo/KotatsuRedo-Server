package io.kotatsuredo.server

import io.kotatsuredo.server.works.TitleNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * These tests are the merge policy. The implementation can be rewritten freely; these cases are what
 * "the same work" actually means (PLAN.md §2.5).
 */
class TitleNormalizerTest {

	private fun norm(value: String) = TitleNormalizer.normalize(value)

	private fun assertMerges(a: String, b: String) = assertTrue(
		TitleNormalizer.differsOnlyByEdition(a, b),
		"expected '$a' and '$b' to merge (got '${norm(a)}' vs '${norm(b)}')",
	)

	private fun assertSplits(a: String, b: String) = assertFalse(
		TitleNormalizer.differsOnlyByEdition(a, b),
		"expected '$a' and '$b' to stay separate (both normalised to '${norm(a)}')",
	)

	// -- editions merge --------------------------------------------------------------------------

	@Test
	fun `edition markers are stripped`() {
		assertMerges("Chainsaw Man", "Chainsaw Man (Colored)")
		assertMerges("Chainsaw Man", "Chainsaw Man [Official]")
		assertMerges("Chainsaw Man", "Chainsaw Man (Full Color)")
		assertMerges("Berserk", "Berserk (Digital)")
		assertMerges("Vagabond", "Vagabond [Remastered]")
	}

	@Test
	fun `a bare year is not part of the identity`() {
		assertMerges("Hellsing", "Hellsing (1997)")
	}

	@Test
	fun `case punctuation and spacing do not matter`() {
		assertEquals(norm("JoJo's Bizarre Adventure"), norm("JoJos Bizarre Adventure"))
		assertEquals(norm("Re:Zero kara Hajimeru"), norm("Re Zero kara Hajimeru"))
		assertEquals(norm("ONE PUNCH-MAN"), norm("one punch man"))
	}

	@Test
	fun `full width characters fold to half width`() {
		assertEquals(norm("ＮＡＲＵＴＯ"), norm("NARUTO"))
	}

	/** Sources disagree about romanization, so both spellings must find the same work. */
	@Test
	fun `macron spellings are indexed under both romanizations`() {
		val keys = TitleNormalizer.keys("Ōsama Ranking")
		assertTrue(keys.contains("osama ranking"), "accent-stripped key missing from $keys")
		assertTrue(keys.contains("ousama ranking"), "macron-expanded key missing from $keys")
	}

	// -- stories split ---------------------------------------------------------------------------

	/**
	 * The most important test here. A sequel merged into its prequel mixes two communities and leaks
	 * spoilers into a thread where nobody has read that far.
	 */
	@Test
	fun `a sequence marker always prevents a merge`() {
		assertSplits("Tower of God", "Tower of God Part 2")
		assertSplits("Tower of God Part 1", "Tower of God Part 2")
		assertSplits("Attack on Titan", "Attack on Titan Season 3")
		assertSplits("Kingdom", "Kingdom 2")
	}

	@Test
	fun `roman and spelled numerals canonicalize to the same entry`() {
		assertEquals(norm("Tower of God Part II"), norm("Tower of God Part 2"))
		assertEquals(norm("Some Series Second Season"), norm("Some Series Season 2"))
		assertMerges("Tower of God Part II (Colored)", "Tower of God Part 2")
	}

	@Test
	fun `a subtitle is not an edition marker`() {
		assertSplits("Berserk", "Berserk The Prototype")
		assertNotEquals(norm("Berserk"), norm("Berserk The Prototype"))
	}

	/**
	 * Dropping every bracketed group would be easier and wrong: plenty of real titles carry their
	 * identity in one.
	 */
	@Test
	fun `a bracketed group that is not edition noise is kept`() {
		assertNotEquals(
			norm("Fate Stay Night"),
			norm("Fate Stay Night (Heavens Feel)"),
		)
	}

	// -- robustness ------------------------------------------------------------------------------

	@Test
	fun `non latin titles survive normalization`() {
		assertEquals(norm("チェンソーマン"), norm("チェンソーマン"))
		assertTrue(norm("나 혼자만 레벨업").isNotEmpty())
		assertNotEquals(norm("チェンソーマン"), norm("ワンピース"))
	}

	@Test
	fun `empty and junk input does not blow up`() {
		assertEquals("", norm(""))
		assertEquals("", norm("   "))
		assertEquals("", norm("(Official)"))
		assertTrue(TitleNormalizer.keys("").isEmpty())
	}

	@Test
	fun `unrelated titles never collide`() {
		assertNotEquals(norm("Naruto"), norm("Bleach"))
		assertNotEquals(norm("One Piece"), norm("One Punch Man"))
	}

	@Test
	fun `sequence signature isolates the series entry`() {
		assertEquals("", TitleNormalizer.sequenceSignature("Tower of God"))
		assertEquals("part 2", TitleNormalizer.sequenceSignature("Tower of God Part 2"))
		assertEquals("part 2", TitleNormalizer.sequenceSignature("Tower of God Part II"))
		assertEquals("season 3", TitleNormalizer.sequenceSignature("Attack on Titan Season 3"))
	}
}
