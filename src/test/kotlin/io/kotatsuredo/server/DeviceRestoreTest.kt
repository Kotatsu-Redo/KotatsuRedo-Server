package io.kotatsuredo.server

import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import java.time.OffsetDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A phone that loses its key gets its account back, and nothing else does.
 *
 * This is the one path where the server hands over an account to a caller who cannot prove it held
 * the key, so what it refuses matters at least as much as what it allows.
 */
class DeviceRestoreTest {

	private val repository by lazy { IdentityRepository(PostgresTestBase.database.exposed) }
	private val pepper = DevicePepper.of("test-pepper")
	private val service by lazy { IdentityService(repository, pepper) }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		PostgresTestBase.truncateAll()
	}

	private fun hello(secret: String, ssaid: String) =
		assertIs<HelloOutcome.Ok>(service.hello(secret, DeviceIdentifiers(ssaid, null)))

	@Test
	fun `a phone that lost its key gets the same account back`() {
		val first = hello("secret-one", "phone-a")
		service.setNickname(first.identity.id, "tsubame")

		// App data cleared: the same hardware, carrying a key the server has never seen.
		val second = hello("secret-two", "phone-a")

		assertFalse(second.created, "an account already existed for this device")
		assertTrue(second.restored)
		assertEquals(first.identity.id, second.identity.id)
		assertEquals("tsubame", second.identity.nickname)
	}

	@Test
	fun `the old key still works after a restore`() {
		val first = hello("secret-one", "phone-a")
		hello("secret-two", "phone-a")

		// Overwriting the stored hash would have been simpler, and would have locked out a second
		// phone legitimately holding the same recovery key.
		val again = hello("secret-one", "phone-a")
		assertFalse(again.created)
		assertFalse(again.restored)
		assertEquals(first.identity.id, again.identity.id)
	}

	@Test
	fun `another phone gets an account of its own`() {
		val first = hello("secret-one", "phone-a")
		val other = hello("secret-two", "phone-b")

		assertTrue(other.created)
		assertFalse(other.restored)
		assertNotEquals(first.identity.id, other.identity.id)
	}

	@Test
	fun `the most recently used account is the one restored`() {
		val older = hello("secret-one", "phone-a")

		// A second account on the same device, as every pre-restore wipe produced. The newest is the
		// defensible guess: it is the one the person was last using.
		val newer = repository.create("zz-newer", "other-secret".toByteArray(), OffsetDateTime.now())
		repository.recordDevice(newer.id, pepper.hash("phone-a"), null, OffsetDateTime.now())
		repository.touch(newer.id, OffsetDateTime.now().plusDays(1))

		val restored = hello("secret-three", "phone-a")
		assertTrue(restored.restored)
		assertEquals(newer.id, restored.identity.id)
		assertNotEquals(older.identity.id, restored.identity.id)
	}

	@Test
	fun `deleting an account stops the device from finding it again`() {
		val first = hello("secret-one", "phone-a")
		service.setNickname(first.identity.id, "tsubame")
		service.deleteEverything(first.identity.id)

		// PRIVACY.md promises a later install on the same phone starts over rather than finding the
		// deleted account. That is only true while the delete takes the device rows and every key
		// with it, which is the foreign key's job and therefore worth checking rather than assuming.
		val after = hello("secret-two", "phone-a")
		assertTrue(after.created, "the deleted account must not be restorable")
		assertFalse(after.restored)
		assertNotEquals(first.identity.id, after.identity.id)
		assertNull(after.identity.nickname)
	}

	@Test
	fun `a banned device is refused before any of this`() {
		hello("secret-one", "phone-a")
		repository.banDevice(pepper.hash("phone-a"), null, "mod-1", "spam", OffsetDateTime.now())

		assertIs<HelloOutcome.DeviceBanned>(
			service.hello("secret-two", DeviceIdentifiers("phone-a", null)),
		)
	}
}
