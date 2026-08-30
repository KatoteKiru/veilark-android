package com.example.veilark.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustSessionFenceTest {
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
  fun timeoutTerminalizesWithoutWaitingForADisconnectedCallback() {
    val fence = TrustSessionFence()
    fence.begin(7L)

    assertTrue(fence.terminalize(7L))
    assertFalse(fence.terminalize(7L))
    assertFalse(fence.accepts(7L))

    fence.begin(8L)
    assertTrue(fence.accepts(8L))
    assertFalse(fence.accepts(7L))
  }
}
