package io.kotatsuredo.server

import io.kotatsuredo.server.auth.RateLimiter
import io.kotatsuredo.server.comments.CommentRepository
import io.kotatsuredo.server.comments.CommentState
import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.identity.sha256
import io.kotatsuredo.server.identity.userIdFrom
import io.kotatsuredo.server.ratings.RatingRepository
import io.kotatsuredo.server.scoring.ScoringRepository
import io.kotatsuredo.server.telemetry.Probe
import io.kotatsuredo.server.telemetry.ProbeOp
import io.kotatsuredo.server.telemetry.Region
import io.kotatsuredo.server.telemetry.TelemetryRepository
import io.kotatsuredo.server.works.WorkRepository
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.system.measureNanoTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in benchmark that exercises production repositories rather than approximating their
 * transactions in SQL. Run with RUN_FEATURE_BENCHMARKS=1 and the normal TEST_DATABASE_* settings.
 */
class FeatureConcurrencyBenchmarkTest {

	@BeforeTest
	fun prepare() {
		PostgresTestBase.requireDatabase()
		assumeTrue(System.getenv("RUN_FEATURE_BENCHMARKS") == "1", "opt-in feature benchmark")
		cleanupBenchmarkRows()
	}

	@AfterTest
	fun cleanup() {
		if (PostgresTestBase.isAvailable && System.getenv("RUN_FEATURE_BENCHMARKS") == "1") {
			cleanupBenchmarkRows()
		}
	}

	@Test
	fun `concurrent community writes and abuse controls remain exact`() {
		val dataSource = PostgresTestBase.database.source
		seedUsers(100)
		val workRepository = WorkRepository(dataSource)
		val comments = CommentRepository(dataSource)
		val ratings = RatingRepository(dataSource)

		val commentWork = workRepository.createWork("Benchmark Comments", 2024, "manga", false)
		val commentId = comments.insert(
			commentWork, null, "benchmark-user-0", null, 0, "Benchmark comment body", false, "en",
			CommentState.VISIBLE,
		)
		val commentMs = concurrentMillis(1_000) { operation ->
			val user = "benchmark-user-${operation % 100}"
			val value = when (operation % 3) {
				0 -> 1
				1 -> -1
				else -> 0
			}
			comments.setVoteAndRecount(commentId, user, value)
		}
		val storedComment = requireNotNull(comments.find(commentId))
		val rawVotes = dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT count(*) FILTER (WHERE value = 1), count(*) FILTER (WHERE value = -1) " +
					"FROM comment_vote WHERE comment_id = ?",
			).use { statement ->
				statement.setLong(1, commentId)
				statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) to rows.getInt(2) }
			}
		}
		assertEquals(rawVotes, storedComment.up to storedComment.down)

		val ratingWork = workRepository.createWork("Benchmark Ratings", 2024, "manga", false)
		val ratingMs = concurrentMillis(1_000) { operation ->
			ratings.setAndAggregate(
				ratingWork,
				"benchmark-user-${operation % 100}",
				(operation % 10) + 1,
			)
		}
		val rawRating = ratings.rawStats(ratingWork)
		val cachedRating = requireNotNull(ratings.aggregate(ratingWork))
		assertEquals(rawRating.first, cachedRating.count)
		assertTrue(abs(rawRating.second - cachedRating.mean) < 0.0001)
		assertEquals(rawRating.third, cachedRating.histogram)

		val identity = IdentityService(
			IdentityRepository(PostgresTestBase.database.exposed, dataSource),
			DevicePepper.of("benchmark-device-pepper"),
		)
		val identityMs = concurrentMillis(400) { operation ->
			identity.hello(
				identitySecret(operation),
				DeviceIdentifiers("benchmark-ssaid-$operation", "benchmark-drm-$operation"),
			)
		}

		val limiter = RateLimiter(maxEntries = 25_000)
		val limiterOperations = 100_000
		val limiterNanos = measureNanoTime {
			repeat(limiterOperations) { operation ->
				limiter.check(
					RateLimiter.Bucket.GENERAL,
					"benchmark-key-${operation % 10_000}",
					TrustTier.NORMAL,
				)
			}
		}

		val probes = buildList {
			repeat(170) { source ->
				ProbeOp.entries.forEach { operation ->
					add(Probe("BENCH_$source", operation, 18, 2, 1, 0, 120, 220))
				}
			}
		}
		val telemetry = TelemetryRepository(dataSource)
		val telemetryInsertNanos = measureNanoTime {
			assertEquals(510, telemetry.record(probes, LocalDate.now(), Region.EU, "benchmark-reporter", 2))
		}
		val telemetryRetryNanos = measureNanoTime {
			assertEquals(510, telemetry.record(probes, LocalDate.now(), Region.EU, "benchmark-reporter", 2))
		}
		assertEquals(510, telemetry.countRows())
		val scoring = ScoringRepository(dataSource)
		var scoreCount = 0
		val scoringNanos = measureNanoTime { scoreCount = scoring.aggregate().size }
		assertEquals(170, scoreCount)

		val lines = listOf(
			"comment_vote_1000_ms=${"%.3f".format(commentMs)}",
			"comment_vote_ops_per_s=${"%.1f".format(1_000_000.0 / commentMs)}",
			"rating_write_1000_ms=${"%.3f".format(ratingMs)}",
			"rating_write_ops_per_s=${"%.1f".format(1_000_000.0 / ratingMs)}",
			"identity_create_400_ms=${"%.3f".format(identityMs)}",
			"identity_create_ops_per_s=${"%.1f".format(400_000.0 / identityMs)}",
			"rate_limiter_100000_ms=${"%.3f".format(limiterNanos / 1_000_000.0)}",
			"rate_limiter_ops_per_s=${"%.1f".format(limiterOperations * 1_000_000_000.0 / limiterNanos)}",
			"telemetry_insert_510_ms=${"%.3f".format(telemetryInsertNanos / 1_000_000.0)}",
			"telemetry_retry_510_ms=${"%.3f".format(telemetryRetryNanos / 1_000_000.0)}",
			"scoring_510_rows_ms=${"%.3f".format(scoringNanos / 1_000_000.0)}",
		)
		val output = Path.of("build", "feature-benchmark-results.txt")
		Files.createDirectories(output.parent)
		Files.writeString(output, lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
	}

	private fun concurrentMillis(operations: Int, threads: Int = 4, action: (Int) -> Unit): Double {
		val next = AtomicInteger()
		val executor = Executors.newFixedThreadPool(threads)
		return try {
			measureNanoTime {
				executor.invokeAll(
					List(threads) {
						Callable {
							while (true) {
								val operation = next.getAndIncrement()
								if (operation >= operations) break
								action(operation)
							}
						}
					},
				).forEach { it.get() }
			}.toDouble() / 1_000_000.0
		} finally {
			executor.shutdownNow()
		}
	}

	private fun seedUsers(count: Int) {
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"INSERT INTO app_user (id, secret_sha256) VALUES (?, ?)",
			).use { statement ->
				repeat(count) { index ->
					statement.setString(1, "benchmark-user-$index")
					statement.setBytes(2, java.security.MessageDigest.getInstance("SHA-256").digest("user-$index".toByteArray()))
					statement.addBatch()
				}
				statement.executeBatch()
			}
		}
	}

	private fun cleanupBenchmarkRows() {
		val identityIds = (0 until 400).map { userIdFrom(sha256(identitySecret(it).toByteArray())) }
		PostgresTestBase.database.source.connection.use { connection ->
			connection.autoCommit = false
			try {
				connection.prepareStatement(
					"DELETE FROM work WHERE canonical_title IN ('Benchmark Comments', 'Benchmark Ratings')",
				).use { it.executeUpdate() }
				connection.prepareStatement("DELETE FROM source_probe_raw WHERE source LIKE 'BENCH\\_%' ESCAPE '\\'")
					.use { it.executeUpdate() }
				connection.prepareStatement(
					"DELETE FROM app_user WHERE id LIKE 'benchmark-user-%' OR id = ANY (?)",
				).use { statement ->
					statement.setArray(1, connection.createArrayOf("text", identityIds.toTypedArray()))
					statement.executeUpdate()
				}
				connection.commit()
			} catch (error: Exception) {
				connection.rollback()
				throw error
			} finally {
				connection.autoCommit = true
			}
		}
	}

	private fun identitySecret(operation: Int) = "benchmark-secret-$operation-${"x".repeat(32)}"
}
