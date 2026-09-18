package io.kotatsuredo.server

import io.kotatsuredo.server.works.DedupeWork
import io.kotatsuredo.server.works.DuplicateFinder
import io.kotatsuredo.server.works.PlannedMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateFinderTest {

	private fun plan(vararg works: DedupeWork, blocked: Set<Pair<Long, Long>> = emptySet()) =
		DuplicateFinder.plan(works.toList(), blocked).map { it.from to it.into }.toSet()

	@Test
	fun `remake renderings join the catalogue work`() {
		val merges = plan(
			DedupeWork(46, "Past Life Returner (Remake 2022)", null, "manga"),
			DedupeWork(89, "Past Life Returner (Remake 2022)", null, "manga"),
			DedupeWork(319, "Past Life Returner (Remake 2022)", null, "manga", activity = 4),
			DedupeWork(
				1199, "Past Life Returner", 2022, "Manhwa",
				trustedTitles = listOf("Past Life Returner", "Jeongsaengja (2022)", "Reincarnator (2022)"),
				externalIds = mapOf("mangaupdates" to setOf("44280046645")),
				activity = 2,
			),
		)
		assertEquals(setOf(46L to 1199L, 89L to 1199L, 319L to 1199L), merges)
	}

	@Test
	fun `a catalogue alt title does not swallow a different catalogue work`() {
		val merges = plan(
			DedupeWork(1, "Past Life Returner", 2022, "manhwa", listOf("Reincarnator"), mapOf("mangaupdates" to setOf("1"))),
			DedupeWork(2, "Reincarnator", 2022, "manhwa", listOf("Reincarnator"), mapOf("mangaupdates" to setOf("2"))),
		)
		assertTrue(merges.isEmpty())
	}

	@Test
	fun `an alt title claimed by two catalogue works is ambiguous`() {
		val merges = plan(
			DedupeWork(1, "Hero Returns", null, "manhwa", listOf("The Return"), mapOf("mangaupdates" to setOf("1"))),
			DedupeWork(2, "Return of the Hero", null, "manhwa", listOf("The Return"), mapOf("mangaupdates" to setOf("2"))),
			DedupeWork(3, "The Return", null, "manga"),
		)
		assertTrue(merges.isEmpty())
	}

	@Test
	fun `catalogue works with the same name but no shared id stay apart`() {
		val merges = plan(
			DedupeWork(1, "Hero", 2019, "manga", externalIds = mapOf("mal" to setOf("10"))),
			DedupeWork(2, "Hero", 2019, "manga", externalIds = mapOf("mangaupdates" to setOf("20"))),
		)
		assertTrue(merges.isEmpty())
	}

	@Test
	fun `sequels, other media and distant years are never merged`() {
		assertTrue(plan(DedupeWork(1, "Tower of God", null, "manhwa"), DedupeWork(2, "Tower of God Part 2", null, "manhwa")).isEmpty())
		assertTrue(plan(DedupeWork(1, "Solo Leveling", null, "novel"), DedupeWork(2, "Solo Leveling", null, "manhwa")).isEmpty())
		assertTrue(plan(DedupeWork(1, "Monster", 1994, "manga"), DedupeWork(2, "Monster", 2015, "manga")).isEmpty())
	}

	@Test
	fun `transitive joins cannot bridge incompatible years`() {
		val merges = plan(
			DedupeWork(1, "Monster", 1994, "manga"),
			DedupeWork(2, "Monster", null, "manga"),
			DedupeWork(3, "Monster", 2015, "manga"),
		)
		assertTrue((1L to 3L) !in merges && (3L to 1L) !in merges)
		assertEquals(1, merges.size)
	}

	@Test
	fun `years written in the title keep separate runs apart`() {
		val merges = plan(
			DedupeWork(26, "Batman (2016-)", null, "comics"),
			DedupeWork(2024, "Batman (1940)", null, "comics"),
			DedupeWork(5437, "Batman (2025)", null, "manga"),
			DedupeWork(7465, "Batman (2016-)", null, "comics"),
		)
		assertEquals(setOf(7465L to 26L), merges)
	}

	@Test
	fun `a run's start year is its year`() {
		assertTrue(plan(DedupeWork(8377, "The Ultimates (2024-)", null, null), DedupeWork(16261, "The Ultimates (2002-2004)", null, "comics")).isEmpty())
	}

	@Test
	fun `earlier merges are re-checked against current guards`() {
		assertTrue(!DuplicateFinder.stillCompatible(DedupeWork(16261, "The Ultimates (2002-2004)", null, "comics"), DedupeWork(8377, "The Ultimates (2024-)", null, null)))
		assertTrue(!DuplicateFinder.stillCompatible(DedupeWork(1, "(ai generated)", null, "hentai"), DedupeWork(2, "(ai generated)", null, "hentai")))
		assertTrue(DuplicateFinder.stillCompatible(DedupeWork(46, "Past Life Returner (Remake 2022)", null, "manga"), DedupeWork(1199, "Past Life Returner", 2022, "Manhwa")))
	}

	@Test
	fun `tag-only titles never match`() {
		assertTrue(plan(DedupeWork(1, "(ai generated)", null, "hentai"), DedupeWork(2, "(ai generated)", null, "hentai")).isEmpty())
	}

	@Test
	fun `an undone merge is not repeated`() {
		val merges = plan(
			DedupeWork(1, "Kagurabachi", null, "manga"),
			DedupeWork(2, "Kagurabachi", null, "manga"),
			blocked = setOf(2L to 1L),
		)
		assertTrue(merges.isEmpty())
	}

	@Test
	fun `generic titles shared by many works are skipped`() {
		val works = (1L..(DuplicateFinder.MAX_WORKS_PER_KEY + 1)).map { DedupeWork(it, "Love", null, "manga") }
		assertTrue(DuplicateFinder.plan(works).isEmpty())
	}

	@Test
	fun `the busiest work survives when none is catalogue-backed`() {
		val merges: List<PlannedMerge> = DuplicateFinder.plan(
			listOf(DedupeWork(1, "Kagurabachi", null, "manga"), DedupeWork(2, "Kagura-bachi", null, "manga", activity = 5)),
		)
		assertEquals(listOf(1L to 2L), merges.map { it.from to it.into })
	}
}
