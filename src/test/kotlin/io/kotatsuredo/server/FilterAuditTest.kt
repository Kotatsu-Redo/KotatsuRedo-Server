package io.kotatsuredo.server

import io.kotatsuredo.server.filter.FilterRepository
import io.kotatsuredo.server.filter.FilterService
import io.kotatsuredo.server.filter.WordFilter
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Measures the word lists against ordinary prose, and prints what fires.
 *
 * This is the closest thing available here to the native-speaker pass the lists want. Nobody on this
 * side reads Thai or Vietnamese, but a term that matches inside an encyclopaedia article is a term
 * that will match inside a comment, and that is the whole false-positive class worth chasing: a word
 * list is dangerous exactly when one of its entries is also an ordinary word.
 *
 * Diagnostic rather than a gate. It runs only when pointed at a corpus:
 *
 *     FILTER_AUDIT_CORPUS=/path/to/corpus ./gradlew test --tests '*FilterAuditTest*' -i
 *
 * The corpus is a directory of `<lang>.txt` files of ordinary text in that language - Wikipedia
 * extracts work well, being broad, dull and profanity-free. Anything this reports is a suspect to be
 * fixed in `tools/curate_wordlists.py`, and the cases it found are kept as assertions in
 * [WordListTest] so they cannot come back.
 */
class FilterAuditTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val repository by lazy { FilterRepository(source) }
	private val filter by lazy { WordFilter(repository) }
	private val service by lazy { FilterService(repository, filter) }

	@BeforeTest
	fun setUp() {
		PostgresTestBase.requireDatabase()
		source.connection.use { connection ->
			connection.createStatement().use {
				it.execute("TRUNCATE filter_block, filter_rule, filter_allow CASCADE")
			}
		}
		service.seed()
		service.reload()
	}

	@Test
	fun `report every rule that fires on ordinary prose`() {
		val directory = System.getenv("FILTER_AUDIT_CORPUS")?.let(::File)
		assumeTrue(directory != null && directory.isDirectory, "FILTER_AUDIT_CORPUS is not set")

		val report = StringBuilder("\n===== FILTER FALSE-POSITIVE AUDIT =====\n")
		directory!!.listFiles { file -> file.extension == "txt" }
			?.sortedBy { it.nameWithoutExtension }
			?.forEach { file ->
				val lang = file.nameWithoutExtension
				val text = file.readText()
				// Sentence-ish chunks, so a hit can be shown in the context it fired in.
				val chunks = text.split(Regex("[.!?\\n。！？]"))
					.map { it.trim() }
					.filter { it.length in 12..400 }

				val hits = mutableMapOf<String, MutableList<String>>()
				chunks.forEach { chunk ->
					filter.match(chunk, lang)?.let { hit ->
						hits.getOrPut("${hit.tier}:${hit.term}") { mutableListOf() }.add(chunk)
					}
				}

				val rate = if (chunks.isEmpty()) 0.0 else hits.values.sumOf { it.size } * 100.0 / chunks.size
				report.append(
					"\n-- %s: %d chunks, %d hits (%.2f%%), %d distinct terms\n"
						.format(lang, chunks.size, hits.values.sumOf { it.size }, rate, hits.size),
				)
				hits.entries
					.sortedByDescending { it.value.size }
					.take(MAX_REPORTED)
					.forEach { (term, samples) ->
						report.append("   %-28s x%-4d  %s\n".format(term, samples.size, samples.first().take(90)))
					}
			}
		println(report)
	}

	private companion object {
		const val MAX_REPORTED = 12
	}
}
