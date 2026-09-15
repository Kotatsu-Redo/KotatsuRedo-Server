package io.kotatsuredo.server

import io.kotatsuredo.server.auth.networkKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The per-network bucket is the only limit a user cannot reset by making a new identity, so it is
 * the one worth attacking. Caddy appends to `X-Forwarded-For` rather than replacing it, which means
 * everything except the final entry is attacker-controlled.
 */
class NetworkKeyTest {

	@Test
	fun `a spoofed prefix does not change the bucket`() {
		val honest = networkKey("203.0.113.10", "10.0.0.2")
		assertEquals(honest, networkKey("1.2.3.4, 203.0.113.10", "10.0.0.2"))
		assertEquals(honest, networkKey("1.2.3.4, 5.6.7.8, 203.0.113.10", "10.0.0.2"))
	}

	@Test
	fun `the bucket is the slash 24, not the address`() {
		assertEquals(networkKey("203.0.113.10", "?"), networkKey("203.0.113.200", "?"))
		assertNotEquals(networkKey("203.0.113.10", "?"), networkKey("203.0.114.10", "?"))
	}

	@Test
	fun `IPv6 addresses are grouped by slash 64`() {
		assertEquals(networkKey("2001:db8::1", "?"), networkKey(" 2001:db8::1 ", "?"))
		assertEquals(networkKey("2001:db8::1", "?"), networkKey("2001:db8::ffff", "?"))
		assertNotEquals(networkKey("2001:db8::1", "?"), networkKey("2001:db8:0:1::1", "?"))
	}

	@Test
	fun `a missing or empty header falls back to the peer`() {
		assertEquals(networkKey(null, "203.0.113.10"), networkKey("  ", "203.0.113.10"))
	}

	@Test
	fun `hostname-shaped input is not interpreted as an address`() {
		assertNotEquals(networkKey("bad.cafe", "?"), networkKey("127.0.0.1", "?"))
	}
}
