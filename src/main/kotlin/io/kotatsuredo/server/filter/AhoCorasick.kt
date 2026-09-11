package io.kotatsuredo.server.filter

/**
 * Aho-Corasick, built once and matched in `O(len(text))` however long the term list is.
 *
 * The point is that fifty thousand terms cost the same as five: the filter runs synchronously in the
 * POST handler (PLAN.md §6), so it must not appear in the latency budget at all. A per-term
 * `contains` loop would be `O(terms x len)` and would.
 *
 * Immutable once built. Updating the lists builds a new automaton and swaps it in, so a rebuild
 * never leaves a half-populated one visible to a request in flight.
 */
class AhoCorasick private constructor(
	private val goto: Array<MutableMap<Char, Int>>,
	private val fail: IntArray,
	private val output: Array<List<String>>,
) {

	data class Match(val term: String, val start: Int, val end: Int) {
		val length: Int get() = end - start
	}

	val isEmpty: Boolean get() = goto.size <= 1

	/** Every match, including overlapping ones - the caller decides which of them survive. */
	fun findAll(text: String): List<Match> {
		if (isEmpty || text.isEmpty()) return emptyList()
		val matches = mutableListOf<Match>()
		var state = ROOT
		for (index in text.indices) {
			val character = text[index]
			state = advance(state, character)
			for (term in output[state]) {
				matches.add(Match(term, index - term.length + 1, index + 1))
			}
		}
		return matches
	}

	/** Stops at the first hit. What the filter itself uses, since one match is enough to reject. */
	fun findFirst(text: String): Match? {
		if (isEmpty || text.isEmpty()) return null
		var state = ROOT
		for (index in text.indices) {
			state = advance(state, text[index])
			output[state].firstOrNull()?.let { term ->
				return Match(term, index - term.length + 1, index + 1)
			}
		}
		return null
	}

	private fun advance(from: Int, character: Char): Int {
		var state = from
		while (state != ROOT && goto[state][character] == null) state = fail[state]
		return goto[state][character] ?: ROOT
	}

	companion object {

		private const val ROOT = 0

		fun build(terms: Collection<String>): AhoCorasick {
			val goto = mutableListOf<MutableMap<Char, Int>>(mutableMapOf())
			val output = mutableListOf<MutableList<String>>(mutableListOf())

			for (term in terms) {
				if (term.isEmpty()) continue
				var state = ROOT
				for (character in term) {
					state = goto[state].getOrPut(character) {
						goto.add(mutableMapOf())
						output.add(mutableListOf())
						goto.size - 1
					}
				}
				output[state].add(term)
			}

			val fail = IntArray(goto.size)
			val queue = ArrayDeque<Int>()
			goto[ROOT].values.forEach { child ->
				fail[child] = ROOT
				queue.addLast(child)
			}
			while (queue.isNotEmpty()) {
				val state = queue.removeFirst()
				for ((character, next) in goto[state]) {
					var candidate = fail[state]
					while (candidate != ROOT && goto[candidate][character] == null) {
						candidate = fail[candidate]
					}
					fail[next] = goto[candidate][character]?.takeIf { it != next } ?: ROOT
					// A suffix link's outputs are also matches ending here - `ass` inside `crass` -
					// so they are folded in at build time rather than walked on every character.
					output[next].addAll(output[fail[next]])
					queue.addLast(next)
				}
			}

			return AhoCorasick(
				goto = goto.toTypedArray(),
				fail = fail,
				output = output.map { it.toList() }.toTypedArray(),
			)
		}
	}
}
