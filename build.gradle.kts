plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.serialization)
	application
}

group = "io.kotatsuredo"
version = "0.1.0"

/**
 * The policy documents live in `legal/` as Markdown, because that is what makes a change to a privacy
 * notice reviewable as a diff. They are copied into the jar so the running instance serves exactly
 * the text that was committed - a notice nobody can open is not published.
 */
tasks.named<ProcessResources>("processResources") {
	from("legal") {
		into("legal")
		include("*.md")
	}
}

kotlin {
	jvmToolchain(17)
}

application {
	mainClass.set("io.kotatsuredo.server.ApplicationKt")
}

dependencies {
	implementation(libs.ktor.server.core)
	implementation(libs.ktor.server.netty)
	implementation(libs.ktor.server.content.negotiation)
	implementation(libs.ktor.server.status.pages)
	implementation(libs.ktor.server.call.logging)
	implementation(libs.ktor.server.call.id)
	implementation(libs.ktor.server.default.headers)
	implementation(libs.ktor.serialization.json)

	implementation(libs.logback.classic)
	implementation(libs.hikaricp)
	implementation(libs.postgresql)
	implementation(libs.flyway.core)
	runtimeOnly(libs.flyway.postgresql)

	implementation(libs.exposed.core)
	implementation(libs.exposed.jdbc)
	implementation(libs.exposed.java.time)

	testImplementation(libs.ktor.server.test.host)
	// Lets the route tests deserialize responses into the same DTOs the routes serialize, so a
	// renamed JSON field fails a test rather than quietly breaking the app.
	testImplementation(libs.ktor.client.content.negotiation)
	testImplementation(libs.kotlin.test)
	testImplementation(libs.junit.jupiter)
	testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
	useJUnitPlatform()
	testLogging {
		events("passed", "skipped", "failed")
	}

	// Integration tests take a Postgres from the environment rather than starting one (see
	// PostgresTestBase). Pass the settings through to the test JVM.
	listOf("TEST_DATABASE_URL", "TEST_DATABASE_USER", "TEST_DATABASE_PASSWORD").forEach { key ->
		System.getenv(key)?.let { environment(key, it) }
	}
}
