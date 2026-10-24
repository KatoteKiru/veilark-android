package com.example.veilark.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelBlockFenceTest {
  @Test
  fun lateBlockCannotReplaceAnAdmittedEngine() {
    val fence = TunnelBlockFence()
    val generation = requireNotNull(fence.request())

    fence.admitEngine()

    assertNull(fence.establishIfCurrent(generation) { "block" })
    assertNull(fence.request())
  }

  @Test
  fun confirmedEngineTeardownAdmitsANewBlock() {
    val fence = TunnelBlockFence()
    fence.admitEngine()
    fence.engineReleased()

    val generation = requireNotNull(fence.request())

    assertEquals("block", fence.establishIfCurrent(generation) { "block" })
  }

  @Test
  fun onlyTheNewestUncancelledRequestEstablishes() {
    val fence = TunnelBlockFence()
    val first = requireNotNull(fence.request())
    val second = requireNotNull(fence.request())

    assertNull(fence.establishIfCurrent(first) { "block" })
    assertTrue(fence.hasRequest())

    fence.cancel()

    assertFalse(fence.hasRequest())
    assertNull(fence.establishIfCurrent(second) { "block" })
    assertNull(fence.establishIfCurrent(0L) { "block" })
  }

  @Test
  fun onlyEngineFailuresWithRetainedConsentBlockTraffic() {
    assertTrue(killSwitchShouldBlock(true, FailureReason.EngineFailure, vpnConsentHeld = true))
    assertTrue(killSwitchShouldBlock(true, FailureReason.Timeout, vpnConsentHeld = true))

    assertFalse(killSwitchShouldBlock(false, FailureReason.EngineFailure, vpnConsentHeld = true))
    assertFalse(killSwitchShouldBlock(true, FailureReason.EngineFailure, vpnConsentHeld = false))
    assertFalse(killSwitchShouldBlock(true, FailureReason.Revoked, vpnConsentHeld = true))
    assertFalse(killSwitchShouldBlock(true, FailureReason.PermissionDenied, vpnConsentHeld = true))
  }
}
