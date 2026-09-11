package io.kotatsuredo.server.routes

/**
 * Enough Markdown to render the policy documents, and no more.
 *
 * The documents are Markdown because that is what makes them reviewable: a change to a privacy
 * notice should read as a diff a person can check, not as an HTML edit. But they also have to be
 * *reachable* - a privacy notice nobody can open is not published - so the instance serves them, and
 * that needs a renderer.
 *
 * Sixty lines rather than a dependency, because the input is four files in this repository rather
 * than arbitrary user content. It handles exactly what those files use: headings, paragraphs, lists,
 * tables, links, emphasis, code and rules. Anything else is passed through escaped, which is the
 * right failure: a document that renders plainly is fine, one that renders someone's HTML is not.
 */
object Markdown {

	fun render(source: String, title: String): String {
		val body = StringBuilder()
		val lines = source.replace("\r\n", "\n").split("\n")
		var index = 0
		var inList = false
		var inItem = false
		var inParagraph = false

		fun closeItem() {
			if (inItem) { body.append("</li>\n"); inItem = false }
		}

		fun closeBlocks() {
			closeItem()
			if (inList) { body.append("</ul>\n"); inList = false }
			if (inParagraph) { body.append("</p>\n"); inParagraph = false }
		}

		while (index < lines.size) {
			val line = lines[index]
			val trimmed = line.trim()

			when {
				trimmed.isEmpty() -> closeBlocks()

				trimmed.startsWith("|") -> {
					closeBlocks()
					index = renderTable(lines, index, body)
					continue
				}

				trimmed.startsWith("#") -> {
					closeBlocks()
					val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
					body.append("<h$level>").append(inline(trimmed.drop(level).trim())).append("</h$level>\n")
				}

				trimmed == "---" || trimmed == "***" -> {
					closeBlocks()
					body.append("<hr>\n")
				}

				trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
					if (inParagraph) { body.append("</p>\n"); inParagraph = false }
					closeItem()
					if (!inList) { body.append("<ul>\n"); inList = true }
					body.append("<li>").append(inline(trimmed.drop(2)))
					inItem = true
				}

				trimmed.startsWith("> ") -> {
					closeBlocks()
					body.append("<blockquote>").append(inline(trimmed.drop(2))).append("</blockquote>\n")
				}

				// A plain line directly under a list item is that item continuing onto a second line,
				// not a new paragraph. Treating it as one breaks every wrapped bullet out of its list,
				// which is most of them in a document written to a column width.
				inItem -> body.append(" ").append(inline(trimmed))

				else -> {
					if (inList) { body.append("</ul>\n"); inList = false }
					if (!inParagraph) { body.append("<p>"); inParagraph = true } else body.append(" ")
					body.append(inline(trimmed))
				}
			}
			index++
		}
		closeBlocks()
		return page(title, body.toString())
	}

	private fun renderTable(lines: List<String>, start: Int, body: StringBuilder): Int {
		var index = start
		val rows = mutableListOf<List<String>>()
		var headerRows = 0
		var seenSeparator = false

		while (index < lines.size && lines[index].trim().startsWith("|")) {
			val cells = lines[index].trim().trim('|').split("|").map { it.trim() }
			val isSeparator = cells.isNotEmpty() &&
				cells.all { it.isNotEmpty() && it.all { c -> c == '-' || c == ':' } }
			if (isSeparator) {
				// Everything collected so far was the header - unless it was blank, which is how a
				// two-column table with no header is written.
				headerRows = rows.count { row -> row.any { it.isNotEmpty() } }
				seenSeparator = true
				index++
				continue
			}
			if (!seenSeparator && cells.all { it.isEmpty() }) {
				index++
				continue
			}
			rows.add(cells)
			index++
		}
		if (rows.isEmpty()) return index

		body.append("<table>\n")
		rows.forEachIndexed { rowIndex, cells ->
			val tag = if (rowIndex < headerRows) "th" else "td"
			body.append("<tr>")
			cells.forEach { body.append("<$tag>").append(inline(it)).append("</$tag>") }
			body.append("</tr>\n")
		}
		body.append("</table>\n")
		return index
	}

	/** Escapes first, then applies the inline forms, so nothing in the source can inject markup. */
	private fun inline(raw: String): String {
		var text = raw
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")

		text = CODE.replace(text) { "<code>${it.groupValues[1]}</code>" }
		text = LINK.replace(text) { match ->
			val href = match.groupValues[2]
			// Only http(s) and same-origin paths. A `javascript:` href in a policy document would be
			// a strange thing to find, and an easy thing to refuse.
			if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("/")) {
				"""<a href="$href">${match.groupValues[1]}</a>"""
			} else {
				match.groupValues[1]
			}
		}
		text = BOLD.replace(text) { "<strong>${it.groupValues[1]}</strong>" }
		text = ITALIC.replace(text) { "<em>${it.groupValues[1]}</em>" }
		return text
	}

	private val CODE = Regex("`([^`]+)`")
	private val LINK = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""")
	private val BOLD = Regex("""\*\*([^*]+)\*\*""")
	private val ITALIC = Regex("""(?<![*\w])\*([^*\n]+)\*(?![*\w])""")

	private fun page(title: String, body: String) = """
		<!doctype html>
		<html lang="en">
		<head>
		<meta charset="utf-8">
		<meta name="viewport" content="width=device-width, initial-scale=1">
		<title>${title.replace("<", "&lt;")}</title>
		<style>
			:root { color-scheme: light dark; }
			body {
				margin: 0 auto; padding: 32px 20px 64px; max-width: 44rem;
				font: 16px/1.65 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
			}
			h1 { font-size: 1.6rem; }
			h2 { font-size: 1.2rem; margin-top: 2.2rem; }
			h3 { font-size: 1.02rem; margin-top: 1.6rem; }
			li { margin: .3rem 0; }
			hr { border: none; border-top: 1px solid rgba(128,128,128,.3); margin: 2rem 0; }
			table { border-collapse: collapse; width: 100%; margin: 1rem 0; display: block; overflow-x: auto; }
			th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid rgba(128,128,128,.3); vertical-align: top; }
			code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .92em; }
			blockquote { margin: 1rem 0; padding-left: 1rem; border-left: 3px solid rgba(128,128,128,.4); opacity: .9; }
			nav { margin-bottom: 2rem; font-size: .92rem; opacity: .8; }
		</style>
		</head>
		<body>
		<nav><a href="/rules">Rules</a> · <a href="/terms">Terms</a> ·
		<a href="/content-policy">Content policy</a> · <a href="/privacy">Privacy</a></nav>
		$body
		</body>
		</html>
	""".trimIndent()
}
