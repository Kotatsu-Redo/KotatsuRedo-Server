package io.kotatsuredo.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bearer secret *is* the identity (PLAN.md §1), so one log line containing an `Authorization`
 * header would hand out accounts. §8 lists "secret leaks via a log line" as a risk whose mitigation is
 * "assert it in a test" - this is that test.
 *
 * It captures everything written to the root logger during a request carrying a credential and a
 * token in the query string, then asserts neither appears anywhere in the output.
 */
class LoggingRedactionTest {

	private val appender = ListAppender<ILoggingEvent>()
	private val root: Logger get() = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

	@BeforeTest
	fun attach() {
		appender.start()
		root.addAppender(appender)
	}

	@AfterTest
	fun detach() {
		root.detachAppender(appender)
		appender.stop()
	}

	@Test
	fun `no log line contains the bearer secret`() = testApplication {
		application { module({ true }) }

		client.get("/v1/health?token=$QUERY_SECRET") {
			header(HttpHeaders.Authorization, "Bearer $HEADER_SECRET")
			header("Cookie", "session=$COOKIE_SECRET")
		}

		val logged = appender.list.joinToString("\n") { it.formattedMessage }

		assertTrue(logged.contains("/v1/health"), "expected the request to be logged at all, got: $logged")
		assertFalse(logged.contains(HEADER_SECRET), "Authorization header leaked into logs:\n$logged")
		assertFalse(logged.contains(COOKIE_SECRET), "Cookie leaked into logs:\n$logged")
		assertFalse(logged.contains(QUERY_SECRET), "Query string leaked into logs:\n$logged")
		assertFalse(logged.contains("Bearer", ignoreCase = true), "the word Bearer appeared in logs:\n$logged")
	}

	private companion object {
		// Distinctive so a partial leak still trips the assertion.
		const val HEADER_SECRET = "zzsecretheaderzz00112233445566778899"
		const val COOKIE_SECRET = "zzsecretcookiezz00112233445566778899"
		const val QUERY_SECRET = "zzsecretqueryzz00112233445566778899"
	}
}
