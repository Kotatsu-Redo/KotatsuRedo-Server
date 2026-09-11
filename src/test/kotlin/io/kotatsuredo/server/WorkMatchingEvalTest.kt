package io.kotatsuredo.server

import io.kotatsuredo.server.works.TitleNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

@Serializable
data class EvalPair(val a: String, val b: String, val category: String, val work: String? = null)

@Serializable
data class EvalFixture(
	val source: String,
	val note: String,
	val positives: List<EvalPair>,
	val negatives: List<EvalPair>,
	/** Pairs MangaDex lists apart but our policy merges. Kept visible, excluded from the metric. */
	val excludedNegatives: List<EvalPair> = emptyList(),
)

/** Swap in the real resolver here when M2b lands; the numbers below are its baseline to beat. */
fun interface TitleMatcher {
	fun matches(a: String, b: String): Boolean
}

data class EvalResult(
	val truePositives: Int,
	val falseNegatives: Int,
	val falsePositives: Int,
	val trueNegatives: Int,
) {
	val precision: Double get() = if (truePositives + falsePositives == 0) 1.0
	else truePositives.toDouble() / (truePositives + falsePositives)

	val recall: Double get() = if (truePositives + falseNegatives == 0) 0.0
	else truePositives.toDouble() / (truePositives + falseNegatives)
}

/**
 * The ground truth for work matching, harvested rather than invented.
 *
 * Positives are titles MangaDex records for the *same* work, so the labels come from a third party
 * instead of from whoever wrote the matcher. Negatives are different works that share an author or a
 * tag - deliberately the hard cases, because random negatives would flatter anything.
 *
 * **Precision is the metric that matters.** A missed merge is an annoyance a user can report; a wrong
 * merge silently mixes two communities and two spoiler horizons, and nothing claws that back. The
 * assertions below are therefore hard on precision and merely descriptive about recall (PLAN.md §7).
 */
class WorkMatchingEvalTest {

	private val fixture: EvalFixture by lazy {
		val stream = requireNotNull(javaClass.getResourceAsStream("/eval/work-matching.json")) {
			"evaluation fixture missing"
		}
		Json { ignoreUnknownKeys = true }.decodeFromString(stream.bufferedReader().readText())
	}

	/** What the resolver does today: exact normalized-key overlap, with the sequence-marker guard. */
	private val exactNormalizer = TitleMatcher { a, b ->
		val keysA = TitleNormalizer.keys(a)
		val keysB = TitleNormalizer.keys(b)
		keysA.isNotEmpty() && keysA.intersect(keysB).isNotEmpty() &&
			TitleNormalizer.sequenceSignature(a) == TitleNormalizer.sequenceSignature(b)
	}

	private fun evaluate(matcher: TitleMatcher, categories: Set<String>? = null): EvalResult {
		val positives = fixture.positives.filter { categories == null || it.category in categories }
		val tp = positives.count { matcher.matches(it.a, it.b) }
		val fp = fixture.negatives.count { matcher.matches(it.a, it.b) }
		return EvalResult(
			truePositives = tp,
			falseNegatives = positives.size - tp,
			falsePositives = fp,
			trueNegatives = fixture.negatives.size - fp,
		)
	}

	@Test
	fun `report the current baseline`() {
		val near = evaluate(exactNormalizer, setOf("near_variant"))
		val distinct = evaluate(exactNormalizer, setOf("distinct_rendering"))
		val cross = evaluate(exactNormalizer, setOf("cross_script"))
		val all = evaluate(exactNormalizer)

		println(
			"""
			|
			|=== Work matching baseline: exact normalized keys ===
			|fixture: ${fixture.positives.size} positives, ${fixture.negatives.size} hard negatives
			|
			|near_variant        recall=${"%.3f".format(near.recall)}   (${near.truePositives}/${near.truePositives + near.falseNegatives})
			|                    punctuation, spacing, romanisation - what the normalizer exists for
			|distinct_rendering  recall=${"%.3f".format(distinct.recall)}   (${distinct.truePositives}/${distinct.truePositives + distinct.falseNegatives})
			|                    a different translation of the same work
			|cross_script        recall=${"%.3f".format(cross.recall)}
			|
			|precision           ${"%.4f".format(all.precision)}  (${all.falsePositives} wrong merges in ${fixture.negatives.size})
			|
			|Read this as: title normalisation solves the near-variant case and essentially nothing
			|else. ${distinct.falseNegatives + cross.falseNegatives} of ${fixture.positives.size} positives are unreachable from title text alone,
			|which is why the resolver leans on external ids, cover hashing and trigram similarity
			|(PLAN.md 2.4). M2b has to move those two rows, not this one.
			""".trimMargin(),
		)
		// Printed so a regression is diagnosable from the CI log rather than needing a local repro.
		fixture.positives.filter { it.category == "near_variant" && !exactNormalizer.matches(it.a, it.b) }
			.take(20)
			.forEach { println("MISSED near_variant: '${it.a}' <> '${it.b}'") }
		fixture.negatives.filter { exactNormalizer.matches(it.a, it.b) }
			.take(20)
			.forEach { println("WRONG MERGE [${it.category}]: '${it.a}' <> '${it.b}'") }
	}

	/**
	 * The guard that matters. If this ever fails, the normalizer has started merging works that a
	 * third party says are different - which is the failure mode §2.7 refuses to accept.
	 */
	@Test
	fun `precision on hard negatives stays at or above 99 percent`() {
		val result = evaluate(exactNormalizer)
		assertTrue(
			result.precision >= 0.99,
			"precision ${result.precision} below 0.99: ${result.falsePositives} wrong merges out of " +
				"${fixture.negatives.size} hard negatives",
		)
	}

	/**
	 * Not a quality bar, a regression alarm: it catches a change that quietly stops matching anything,
	 * which a precision-only check would happily call perfect.
	 */
	@Test
	fun `near variant recall does not collapse`() {
		// Currently 1.000. Held at 0.95 so a genuine edge case can be added to the fixture without
		// failing the build, while any real regression in folding still trips it.
		val result = evaluate(exactNormalizer, setOf("near_variant"))
		assertTrue(
			result.recall >= 0.95,
			"near-variant recall fell to ${result.recall}; the normalizer has stopped folding " +
				"punctuation, spacing and romanization variants together",
		)
	}

	@Test
	fun `the fixture is real and large enough to mean something`() {
		assertTrue(fixture.positives.size > 500, "only ${fixture.positives.size} positives")
		assertTrue(fixture.negatives.size > 300, "only ${fixture.negatives.size} negatives")
		assertTrue(
			fixture.negatives.all { it.category in setOf("same_author", "same_tag") },
			"negatives must be hard cases, not random pairs",
		)
	}
}
