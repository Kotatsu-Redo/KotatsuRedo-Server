package io.kotatsuredo.server

import io.kotatsuredo.server.routes.Markdown
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The renderer that puts the policy documents on screen.
 *
 * It only has to handle four files in this repository, but those four are the terms and the privacy
 * notice, so "renders roughly right" is not the bar: a mangled retention table or a bullet that
 * escaped its list is a policy that says something slightly different from the one that was
 * reviewed.
 */
class MarkdownTest {

	private fun render(source: String) = Markdown.render(source, "Test")

	@Test
	fun `a wrapped list item stays one item`() {
		// Every one of these documents is written to a column width, so almost every bullet wraps.
		// Treating the second line as a new paragraph broke them all out of their lists.
		val html = render(
			"""
			- **There is no account.** No email address, no password, no phone
			  number, no name.
			- You can erase everything instantly.
			""".trimIndent(),
		)
		assertContains(html, "no phone number, no name.")
		assertTrue(html.count { it == '<' } > 0)
		assertEquals(2, Regex("<li>").findAll(html).count(), "expected exactly two list items")
		assertFalse(
			html.substringAfter("<ul>").substringBefore("</ul>").contains("<p>"),
			"a wrapped bullet escaped its list:\n$html",
		)
	}

	@Test
	fun `a table with a header keeps it`() {
		val html = render(
			"""
			| Value | What it is |
			|---|---|
			| `ANDROID_ID` | An identifier |
			""".trimIndent(),
		)
		assertContains(html, "<th>Value</th>")
		assertContains(html, "<td><code>ANDROID_ID</code></td>")
	}

	@Test
	fun `a table with no header does not invent one`() {
		// The retention table is written `| | |` precisely because it has no headings. Promoting its
		// first row of data to a header mislabels the document's own retention periods.
		val html = render(
			"""
			| | |
			|---|---|
			| Comments | Until you delete them |
			| Raw telemetry | 7 days |
			""".trimIndent(),
		)
		assertFalse(html.contains("<th>"), "a headerless table grew a header:\n$html")
		assertContains(html, "<td>Comments</td>")
	}

	@Test
	fun `markup in the source cannot escape into the page`() {
		// These are repository files rather than user content, so this is defence in depth - but a
		// renderer that would pass through a script tag is one nobody should reuse later.
		val html = render("A line with <script>alert(1)</script> in it.")
		assertFalse(html.contains("<script>"))
		assertContains(html, "&lt;script&gt;")
	}

	@Test
	fun `only safe link schemes survive`() {
		val html = render("[ok](https://example.com) [path](/privacy) [bad](javascript:alert(1))")
		assertContains(html, """<a href="https://example.com">ok</a>""")
		assertContains(html, """<a href="/privacy">path</a>""")
		assertFalse(html.contains("javascript:"), "a javascript: href was rendered:\n$html")
		assertContains(html, "bad")
	}

	@Test
	fun `headings, emphasis, code and rules render`() {
		val html = render(
			"""
			## What the server holds

			Text with **bold**, *italic* and `code`.

			---
			""".trimIndent(),
		)
		assertContains(html, "<h2>What the server holds</h2>")
		assertContains(html, "<strong>bold</strong>")
		assertContains(html, "<em>italic</em>")
		assertContains(html, "<code>code</code>")
		assertContains(html, "<hr>")
	}

	@Test
	fun `the real documents render without leaving a block open`() {
		listOf("TERMS", "PRIVACY", "CONTENT-POLICY").forEach { name ->
			val source = requireNotNull(
				javaClass.getResourceAsStream("/legal/$name.md")?.bufferedReader()?.readText(),
			) { "legal/$name.md is not packaged" }
			val html = Markdown.render(source, name)

			listOf("ul", "li", "p", "table", "tr").forEach { tag ->
				val open = Regex("<$tag[ >]").findAll(html).count()
				val close = Regex("</$tag>").findAll(html).count()
				assertEquals(open, close, "[$name] unbalanced <$tag>: $open open, $close closed")
			}
			assertFalse(html.contains("<script"), "[$name] rendered a script tag")
		}
	}

	private fun assertEquals(expected: Int, actual: Int, message: String) =
		kotlin.test.assertEquals(expected, actual, message)
}
