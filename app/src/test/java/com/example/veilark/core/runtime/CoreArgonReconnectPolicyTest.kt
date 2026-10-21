package com.example.veilark.core.runtime

import org.junit.Assert.*
import org.junit.Test

class CoreArgonReconnectPolicyTest {
  @Test fun onlyExactRunningOwnedCancellationMayBePlanned() {
    assertTrue(CoreArgonReconnectPolicy.isPlannedNetworkRotation(true, true, false, 4, 2))
    for (outcome in listOf(null, 0, 3, 24, 26, 28, 29, -3, 99)) {
      assertFalse(CoreArgonReconnectPolicy.isPlannedNetworkRotation(true, true, false, 4, outcome))
    }
    assertFalse(CoreArgonReconnectPolicy.isPlannedNetworkRotation(false, true, false, 4, 2))
    assertFalse(CoreArgonReconnectPolicy.isPlannedNetworkRotation(true, false, false, 4, 2))
    assertFalse(CoreArgonReconnectPolicy.isPlannedNetworkRotation(true, true, true, 4, 2))
    for (terminal in listOf(null, 0, 3, 5, 6, -3)) {
      assertFalse(CoreArgonReconnectPolicy.isPlannedNetworkRotation(true, true, false, terminal, 2))
    }
  }

  @Test fun manyNetworkRotationsDoNotConsumeOrErasePriorFailures() {
    for (initial in 0..5) {
      var failures = initial
      repeat(12) {
        val retry = CoreArgonReconnectPolicy.next(failures, true)
        failures = retry.failureCount
        assertEquals(initial, failures)
        assertEquals(250L, retry.delayMillis)
      }
    }
  }

  @Test fun rotationsCannotHideRepeatedGenuineFailures() {
    var failures = 0
    repeat(5) {
      failures = CoreArgonReconnectPolicy.next(failures, false).failureCount
      repeat(3) { failures = CoreArgonReconnectPolicy.next(failures, true).failureCount }
    }
    assertEquals(5, failures)
    try { CoreArgonReconnectPolicy.next(failures, false); fail("Sixth real failure waived") }
    catch (_: IllegalStateException) { }
  }

  @Test fun unexpectedFailuresRetainExistingBoundedBackoff() {
    assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L),
      (0..4).map { CoreArgonReconnectPolicy.next(it, false).delayMillis })
  }
}
