package com.example.veilark.core.runtime

import org.junit.Assert.*
import org.junit.Test

class CoreTurnGrantTest {
  @Test fun uriUsesUdpOnlyAndRejectsAmbiguousOrSecretBearingForms() {
    val endpoint = CoreTurnEndpoint.parse("TURN:Relay.Example:3478?transport=udp")
    assertEquals("relay.example", endpoint.host)
    assertEquals(3478, endpoint.port)
    assertEquals(3478, CoreTurnEndpoint.parse("turn:192.0.2.1").port)
    for (value in listOf("turns:relay.example", "turn:relay.example?transport=tcp",
      "turn://relay.example", "turn:user:password@relay.example", "turn:relay.example#key",
      "turn:relay.example:0", "turn:relay.example:03478", "turn:relay.example:65536",
      "turn:relay.example?transport=udp&extra=x", "turn:192.000.2.1", "turn:2130706433",
      "turn:-relay.example", "turn:relay..example", "turn:relay.example:3478/", "turn:relay.example\n")) {
      val failure = assertThrows(IllegalArgumentException::class.java) { CoreTurnEndpoint.parse(value) }
      assertFalse(failure.message.orEmpty().contains(value))
    }
  }

  @Test fun borrowedCredentialsAreDefensiveAndWipedOnSuccessOrException() {
    val originalUser = "test-user".toByteArray()
    val originalPassword = "synthetic-secret".toByteArray()
    val grant = CoreTurnGrant.create("turn:relay.example", originalUser, originalPassword, 60, 100)
    originalUser.fill(0); originalPassword.fill(0)
    var borrowedUser = byteArrayOf()
    var borrowedPassword = byteArrayOf()
    grant.withCredentialBytes(101) { user, password, remaining ->
      assertEquals("test-user", String(user))
      assertEquals("synthetic-secret", String(password))
      assertEquals(59_999L, remaining)
      borrowedUser = user; borrowedPassword = password
    }
    assertTrue(borrowedUser.all { it == 0.toByte() })
    assertTrue(borrowedPassword.all { it == 0.toByte() })
    assertThrows(IllegalStateException::class.java) {
      grant.withCredentialBytes(102) { user, password, _ ->
        borrowedUser = user; borrowedPassword = password
        throw IllegalStateException("synthetic failure")
      }
    }
    assertTrue(borrowedUser.all { it == 0.toByte() })
    assertTrue(borrowedPassword.all { it == 0.toByte() })
    assertFalse(grant.toString().contains("test-user"))
    assertFalse(grant.toString().contains("synthetic-secret"))
    grant.close()
  }

  @Test fun expiryClockRollbackAndCloseRejectBeforeCallingConsumer() {
    val grant = CoreTurnGrant.create("turn:relay.example", byteArrayOf(65), byteArrayOf(66), 1, 100)
    assertEquals(1L, grant.remainingMillis(1099))
    for (now in listOf(99L, 1100L, Long.MAX_VALUE)) {
      assertThrows(IllegalStateException::class.java) {
        grant.withCredentialBytes(now) { _, _, _ -> fail("Expired grant reached consumer") }
      }
    }
    // Close from inside a callback is safe; no callback executes under its monitor.
    grant.withCredentialBytes(101) { _, _, _ -> grant.close() }
    assertThrows(IllegalStateException::class.java) { grant.remainingMillis(102) }
    grant.close()
  }

  @Test fun malformedCredentialOrLifetimeNeverEchoesInput() {
    for ((ttl, now) in listOf(0L to 0L, 86_401L to 0L, 1L to -1L, 1L to Long.MAX_VALUE)) {
      assertThrows(IllegalArgumentException::class.java) {
        CoreTurnGrant.create("turn:relay.example", byteArrayOf(65), byteArrayOf(66), ttl, now)
      }
    }
    for ((user, password) in listOf(byteArrayOf() to byteArrayOf(66),
      byteArrayOf(65) to byteArrayOf(10), ByteArray(513) { 65 } to byteArrayOf(66))) {
      assertThrows(IllegalArgumentException::class.java) {
        CoreTurnGrant.create("turn:relay.example", user, password, 60, 0)
      }
    }
  }
}
