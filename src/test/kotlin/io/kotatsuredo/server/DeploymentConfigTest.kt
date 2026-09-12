package io.kotatsuredo.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that what the code reads from the environment is what the deployment actually supplies.
 *
 * This exists because it did not. `MOD_BOOTSTRAP_USERNAME` was documented in `.env.example`, told to
 * you by `DEPLOY.md`, and read by [Config] - and never passed into the api container, so the first
 * moderator was silently never created and the panel could not be claimed on any real deployment.
 * Nothing failed; there was simply no admin. A unit test cannot see that and neither can a schema
 * test, because the mistake is in the wiring between three files that never mention each other.
 */
class DeploymentConfigTest {

	@Test
	fun `every variable Config reads is passed to the api container`() {
		val missing = configKeys() - composeApiEnvironment()
		assertTrue(
			missing.isEmpty(),
			"docker-compose.yml never passes $missing to the api container, so setting it in .env " +
				"does nothing. Add it to the api service's `environment:` block.",
		)
	}

	@Test
	fun `every variable Config reads is documented in env example`() {
		val documented = ENV_EXAMPLE.readLines()
			.mapNotNull { line -> line.substringBefore('=').trim().takeIf { '=' in line && !line.startsWith('#') } }
			.toSet()
		assertEquals(
			emptySet(), configKeys() - documented,
			"a variable the server reads is not in .env.example, so nobody deploying it would know",
		)
	}

	@Test
	fun `the compose file does not hardcode a port the app configures`() {
		// PORT used to be written as a literal here, which meant editing it in .env did nothing and
		// the api and Caddy could disagree about where the api was listening.
		val environment = COMPOSE.readText().substringAfter("  api:").substringBefore("\n  caddy:")
		assertTrue("PORT: \${PORT" in environment, "PORT must come from the environment, not a literal")
		assertTrue(
			"{\$API_PORT" in CADDYFILE.readText(),
			"the Caddyfile must follow PORT rather than naming a port of its own",
		)
	}

	@Test
	fun `every directory the jar packages is in the docker build context`() {
		// processResources reaches outside src/ for the legal documents. The Dockerfile has to copy
		// those directories too, and nothing fails if it does not: the resource is simply absent and
		// the route serves "this document is missing from this deployment" with a 200. That shipped,
		// and a status-code check did not notice.
		val packaged = Regex("""from\("([^"]+)"\)""")
			.findAll(BUILD_GRADLE.readText())
			.map { it.groupValues[1] }
			.toSet()
		val copied = DOCKERFILE.readText()
		for (directory in packaged) {
			assertTrue(
				Regex("""^COPY\s+$directory""", RegexOption.MULTILINE).containsMatchIn(copied),
				"the jar packages `$directory` but the Dockerfile never copies it, so it will be " +
					"missing from the image while the build still succeeds",
			)
		}
	}

	@Test
	fun `the legal documents are real, not the missing-document stub`() {
		for (name in listOf("TERMS.md", "PRIVACY.md", "CONTENT-POLICY.md")) {
			val file = File(ROOT, "legal/$name")
			assertTrue(file.isFile, "legal/$name is missing")
			assertTrue(file.length() > 2000, "legal/$name is too short to be the real document")
		}
	}

	private fun configKeys(): Set<String> = Regex("""env\.(?:optional|required)\("([A-Z_0-9]+)"\)""")
		.findAll(CONFIG.readText())
		.map { it.groupValues[1] }
		.toSet()

	/** The keys of the api service's `environment:` block, which is a flat `KEY: value` mapping. */
	private fun composeApiEnvironment(): Set<String> = COMPOSE.readText()
		.substringAfter("  api:")
		.substringBefore("\n  caddy:")
		.substringAfter("    environment:\n")
		.lineSequence()
		.takeWhile { it.startsWith("      ") }
		.mapNotNull { line -> Regex("""^\s+([A-Z_0-9]+):""").find(line)?.groupValues?.get(1) }
		.toSet()

	private companion object {
		val ROOT = File("").absoluteFile
		val CONFIG = File(ROOT, "src/main/kotlin/io/kotatsuredo/server/Config.kt")
		val COMPOSE = File(ROOT, "docker-compose.yml")
		val CADDYFILE = File(ROOT, "deploy/Caddyfile")
		val ENV_EXAMPLE = File(ROOT, ".env.example")
		val DOCKERFILE = File(ROOT, "Dockerfile")
		val BUILD_GRADLE = File(ROOT, "build.gradle.kts")
	}
}
