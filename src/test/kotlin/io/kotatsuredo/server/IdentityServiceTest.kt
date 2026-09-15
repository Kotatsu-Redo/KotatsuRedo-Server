package io.kotatsuredo.server

import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import io.kotatsuredo.server.identity.NicknameResult
import io.kotatsuredo.server.identity.TrustTier
import io.kotatsuredo.server.identity.sha256
import java.time.OffsetDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityServiceTest {

	// Lazy: field initialisers run before @BeforeTest, so eager construction would hit the database
	// before the skip guard could fire.
	private val repository by lazy { IdentityRepository(PostgresTestBase.database.exposed, PostgresTestBase.database.source) }
	private val pepper = DevicePepper.of("test-pepper")
	private val service by lazy { IdentityService(repository, pepper) }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		PostgresTestBase.truncateAll()
	}

	private fun device(ssaid: String = "ssaid-1", drm: String? = "drm-1") = DeviceIdentifiers(ssaid, drm)

	@Test
	fun `first hello creates an identity and the second returns the same one`() {
		val first = service.hello("secret-aaa", device())
		val second = service.hello("secret-aaa", device())

		assertIs<HelloOutcome.Ok>(first)
		assertIs<HelloOutcome.Ok>(second)
		assertTrue(first.created)
		assertFalse(second.created)
		assertEquals(first.identity.id, second.identity.id)
	}

	@Test
	fun `different secrets are different users`() {
		val a = service.hello("secret-aaa", device(ssaid = "a")) as HelloOutcome.Ok
		val b = service.hello("secret-bbb", device(ssaid = "b")) as HelloOutcome.Ok

		assertNotEquals(a.identity.id, b.identity.id)
	}

	/**
	 * The database must never hold anything that could be replayed as a credential. If the raw
	 * secret ever reached a column, a database leak would hand out accounts.
	 */
	@Test
	fun `the raw secret is never stored`() {
		val secret = "secret-plaintext-must-not-persist"
		service.hello(secret, device())

		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { statement ->
				val rows = statement.executeQuery(
					"SELECT id, secret_sha256::text, nickname FROM app_user",
				)
				assertTrue(rows.next())
				val dump = (1..3).joinToString(" ") { rows.getString(it) ?: "" }
				assertFalse(dump.contains(secret), "raw secret found in app_user: $dump")
			}
		}
	}

	@Test
	fun `user id is derived from the secret hash`() {
		val secret = "secret-derivation"
		val outcome = service.hello(secret, device()) as HelloOutcome.Ok

		val expected = io.kotatsuredo.server.identity.userIdFrom(sha256(secret.toByteArray()))
		assertEquals(expected, outcome.identity.id)
	}

	// -- bans ------------------------------------------------------------------------------------

	@Test
	fun `a banned device cannot create an identity at all`() {
		val ssaid = "banned-device"
		repository.banDevice(pepper.hash(ssaid), null, "mod", "testing", OffsetDateTime.now())

		val outcome = service.hello("brand-new-secret", device(ssaid = ssaid))

		assertIs<HelloOutcome.DeviceBanned>(outcome)
		assertNull(repository.findBySecretHash(sha256("brand-new-secret".toByteArray())))
	}

	/**
	 * The factory-reset case: same hardware (DRM id), new ANDROID_ID. It must be allowed through and
	 * flagged, never blocked - DRM ids collide across devices, so auto-banning would hit a stranger
	 * who did nothing (PLAN.md §1).
	 */
	@Test
	fun `a matching drm id on a new ssaid is flagged but not blocked`() {
		val bannedSsaid = "old-device"
		val sharedDrm = "shared-drm"
		repository.banDevice(pepper.hash(bannedSsaid), pepper.hash(sharedDrm), "mod", null, OffsetDateTime.now())

		val outcome = service.hello("secret-after-reset", device(ssaid = "new-device", drm = sharedDrm))

		assertIs<HelloOutcome.Ok>(outcome)
		assertEquals(1, unreviewedFlagCount(), "expected a ban-evasion flag for a human to review")
	}

	@Test
	fun `a device with no drm id still works`() {
		val outcome = service.hello("secret-degoogled", DeviceIdentifiers("degoogled-device", drmId = null))

		assertIs<HelloOutcome.Ok>(outcome)
		assertEquals(0, unreviewedFlagCount())
	}

	// -- nicknames -------------------------------------------------------------------------------

	@Test
	fun `nickname is stored and displayed with a discriminator`() {
		val outcome = service.hello("secret-nick", device()) as HelloOutcome.Ok
		service.setNickname(outcome.identity.id, "raph")

		val updated = service.get(outcome.identity.id)!!
		assertEquals("raph", updated.nickname)
		assertTrue(updated.displayName.startsWith("raph#"))
		assertEquals(4, updated.displayName.substringAfter('#').length)
	}

	@Test
	fun `reserved names are refused including evasive spellings`() {
		val outcome = service.hello("secret-reserved", device()) as HelloOutcome.Ok

		listOf("admin", "Admin", "a d m i n", "adm1n", "@dmin", "M0D").forEach { attempt ->
			assertIs<NicknameResult.Reserved>(
				service.setNickname(outcome.identity.id, attempt),
				"expected '$attempt' to be refused as reserved",
			)
		}
	}

	// -- trust and deletion ----------------------------------------------------------------------

	@Test
	fun `a brand new identity is tier 0`() {
		val outcome = service.hello("secret-tier", device()) as HelloOutcome.Ok
		assertEquals(TrustTier.NEW, service.trustTier(outcome.identity.id))
	}

	@Test
	fun `established trust requires ninety days and thirty active days`() {
		val userId = (service.hello("secret-established", device()) as HelloOutcome.Ok).identity.id
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"UPDATE app_user SET created_at = now() - INTERVAL '91 days' WHERE id = ?",
			).use {
				it.setString(1, userId)
				it.executeUpdate()
			}
			connection.prepareStatement(
				"""
				INSERT INTO user_active_day (user_id, day)
				SELECT ?, CURRENT_DATE - n::int FROM generate_series(0, 28) AS n
				ON CONFLICT DO NOTHING
				""".trimIndent(),
			).use {
				it.setString(1, userId)
				it.executeUpdate()
			}
		}
		assertEquals(TrustTier.NORMAL, service.trustTier(userId))
		assertEquals(TrustTier.NORMAL.level, trustViewTier(userId))

		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement(
				"INSERT INTO user_active_day (user_id, day) VALUES (?, CURRENT_DATE - 29) ON CONFLICT DO NOTHING",
			).use {
				it.setString(1, userId)
				it.executeUpdate()
			}
		}
		assertEquals(TrustTier.ESTABLISHED, service.trustTier(userId))
		assertEquals(TrustTier.ESTABLISHED.level, trustViewTier(userId))
	}

	@Test
	fun `delete removes the user and their device record`() {
		val outcome = service.hello("secret-delete", device()) as HelloOutcome.Ok

		service.deleteEverything(outcome.identity.id)

		assertNull(service.get(outcome.identity.id))
		assertNull(repository.deviceOf(outcome.identity.id))
	}

	private fun unreviewedFlagCount(): Int =
		PostgresTestBase.database.source.connection.use { connection ->
			connection.createStatement().use { statement ->
				val rows = statement.executeQuery(
					"SELECT count(*) FROM ban_evasion_flag WHERE reviewed_at IS NULL",
				)
				rows.next()
				rows.getInt(1)
			}
		}

	private fun trustViewTier(userId: String): Int =
		PostgresTestBase.database.source.connection.use { connection ->
			connection.prepareStatement("SELECT tier FROM user_trust WHERE user_id = ?").use {
				it.setString(1, userId)
				it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
			}
		}
}
