package com.example.veilark.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustSessionFenceTest {
  @Test
  fun reconnectCanBecomeConnectedAgainWithoutReleasingSessionOwnership() {
    val fence = TrustSessionFence()
    fence.begin(11L)
    assertTrue(fence.acceptConnected(11L))
    assertFalse(fence.acceptConnected(11L))

    assertTrue(fence.acceptConnecting(11L))
    assertFalse(fence.isConnected(11L))
    assertTrue(fence.accepts(11L))
    assertTrue(fence.acceptConnected(11L))
    assertTrue(fence.isConnected(11L))
    assertFalse(fence.acceptConnected(11L))
  }

  @Test
  fun staleOrStoppingConnectingCannotReopenTheConnectedGate() {
    val fence = TrustSessionFence()
    fence.begin(12L)
    assertTrue(fence.acceptConnected(12L))
    assertFalse(fence.acceptConnecting(11L))
    assertTrue(fence.isConnected(12L))
    assertTrue(fence.requestStop(12L))
    assertFalse(fence.acceptConnecting(12L))
    assertFalse(fence.acceptConnected(12L))
    assertTrue(fence.terminalize(12L))
    assertFalse(fence.acceptConnecting(12L))
  }

  @Test
  fun lateEventsCannotMutateANewerSession() {
    val fence = TrustSessionFence()
    fence.begin(1L)
    assertTrue(fence.acceptConnected(1L))
    assertFalse(fence.acceptConnected(1L))
    assertTrue(fence.terminalize(1L))

    fence.begin(2L)
    assertFalse(fence.acceptConnected(1L))
    assertFalse(fence.terminalize(1L))
    assertTrue(fence.acceptConnected(2L))
    assertTrue(fence.isConnected(2L))
  }

  @Test
  fun timeoutRetainsOwnershipUntilPostCloseCallback() {
    val fence = TrustSessionFence()
    fence.begin(7L)

    assertTrue(fence.requestStop(7L))
    assertTrue(fence.accepts(7L))
    assertFalse(fence.acceptConnected(7L))
    assertFalse(fence.isConnected(7L))
    assertFalse(fence.requestStop(6L))
    assertTrue(fence.terminalize(7L))
    assertFalse(fence.terminalize(7L))

    fence.begin(8L)
    assertTrue(fence.accepts(8L))
    assertFalse(fence.accepts(7L))
  }

  @Test(expected = IllegalStateException::class)
  fun stoppingSessionBlocksNewAdmissionUntilRealClose() {
    val fence = TrustSessionFence()
    fence.begin(1L)
    fence.requestStop(1L)
    fence.begin(2L)
  }
}
