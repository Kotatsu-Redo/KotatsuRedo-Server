package io.kotatsuredo.server

import io.kotatsuredo.server.identity.DeviceIdentifiers
import io.kotatsuredo.server.identity.DevicePepper
import io.kotatsuredo.server.identity.HelloOutcome
import io.kotatsuredo.server.identity.IdentityRepository
import io.kotatsuredo.server.identity.IdentityService
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device identifiers are abuse-control signals, never account credentials.
 */
class DeviceRestoreTest {

	private val repository by lazy { IdentityRepository(PostgresTestBase.database.exposed, PostgresTestBase.database.source) }
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
	fun `a new key on the same phone creates a separate account`() {
		val first = hello("secret-one", "phone-a")
		service.setNickname(first.identity.id, "tsubame")

		val second = hello("secret-two", "phone-a")

		assertTrue(second.created)
		assertFalse(second.restored)
		assertNotEquals(first.identity.id, second.identity.id)
		assertNull(second.identity.nickname)
	}

	@Test
	fun `the old key still resolves its own account`() {
		val first = hello("secret-one", "phone-a")
		hello("secret-two", "phone-a")

		val again = hello("secret-one", "phone-a")
		assertFalse(again.created)
		assertFalse(again.restored)
		assertEquals(first.identity.id, again.identity.id)
	}

	@Test
	fun `concurrent first hello creates exactly one account`() {
		val ready = CountDownLatch(2)
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val calls = List(2) {
				executor.submit<HelloOutcome.Ok> {
					ready.countDown()
					start.await()
					hello("same-secret", "phone-a")
				}
			}
			ready.await()
			start.countDown()
			val outcomes = calls.map { it.get() }

			assertEquals(1, outcomes.count { it.created })
			assertEquals(1, outcomes.map { it.identity.id }.distinct().size)
		} finally {
			executor.shutdownNow()
		}
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
	fun `device history cannot select an account for a new key`() {
		val older = hello("secret-one", "phone-a")

		val newer = repository.create("zz-newer", "other-secret".toByteArray(), OffsetDateTime.now())
		repository.recordDevice(newer.id, pepper.hash("phone-a"), null, OffsetDateTime.now())
		repository.touch(newer.id, OffsetDateTime.now().plusDays(1))

		val created = hello("secret-three", "phone-a")
		assertTrue(created.created)
		assertFalse(created.restored)
		assertNotEquals(newer.id, created.identity.id)
		assertNotEquals(older.identity.id, created.identity.id)
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
		assertTrue(after.created, "the deleted account must not be recoverable from a device id")
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
