package com.example.veilark.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreBetaReconnectPolicyTest {
  @Test fun onlyOwnTimerWhileStillReadyCanInitiateRotation() {
    assertEquals(false, CoreBetaReconnectPolicy.shouldRotate(1, null, 60_000, 10))
    assertEquals(false, CoreBetaReconnectPolicy.shouldRotate(1, 51_000, 59_999, 10))
    assertEquals(true, CoreBetaReconnectPolicy.shouldRotate(1, 51_000, 60_000, 10))
    assertEquals(false, CoreBetaReconnectPolicy.shouldRotate(1, 30_000, 60_000, 1800))
    assertEquals(false, CoreBetaReconnectPolicy.shouldRotate(1, 30_000, 1_828_999, 1800))
    assertEquals(true, CoreBetaReconnectPolicy.shouldRotate(1, 30_000, 1_829_000, 1800))
    assertEquals(false, CoreBetaReconnectPolicy.shouldRotate(1, 60_000, 59_999, 10))
    for (terminalOrNotReady in listOf(-3, -4, 0, 2, 3)) {
      assertEquals(false, CoreBetaReconnectPolicy.shouldRotate(terminalOrNotReady, 0, 3_600_000, 1800))
    }
  }

  @Test fun repeatedLongNativeFailuresCannotBypassRetryBudget() {
    var failures = 0
    repeat(5) {
      // Each native-failed session had31seconds Ready: the old policy reset forever.
      val planned = CoreBetaReconnectPolicy.shouldRotate(-3, 0, 31_000, 1800)
      assertEquals(false, planned)
      failures = CoreBetaReconnectPolicy.nextFailureCount(failures, planned)
    }
    assertEquals(5, failures)
    try { CoreBetaReconnectPolicy.nextFailureCount(failures, false); org.junit.Assert.fail("Sixth failure accepted") }
    catch (_: IllegalStateException) { }
  }

  @Test fun sixRacingFailuresAfterTimerDecisionStillExhaustTheBudget() {
    var failures = 0
    repeat(5) {
      assertEquals(true, CoreBetaReconnectPolicy.shouldRotate(1, 0, 1_799_000, 1800))
      val completed = CoreBetaReleaseOutcome(true, 3, if (it % 2 == 0) 11 else 16)
      assertEquals(false, completed.allowsPlannedRotation)
      failures = CoreBetaReconnectPolicy.nextFailureCount(failures, completed.allowsPlannedRotation)
    }
    assertEquals(5, failures)
    try { CoreBetaReconnectPolicy.nextFailureCount(failures, false); org.junit.Assert.fail("Racing errors reset budget") }
    catch (_: IllegalStateException) { }
  }

  @Test fun reconnectAttemptsAreBoundedAndBackOff() {
    assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), (1..5).map { CoreBetaReconnectPolicy.delayMillis(it) })
  }
  @Test(expected = IllegalArgumentException::class) fun zeroAttemptRejected() { CoreBetaReconnectPolicy.delayMillis(0) }
  @Test(expected = IllegalArgumentException::class) fun unboundedAttemptRejected() { CoreBetaReconnectPolicy.delayMillis(6) }
  @Test fun repeatedFailuresExhaustBudgetAndHealthySessionResetsIt() {
    var failures = 0
    repeat(5) { failures = CoreBetaReconnectPolicy.nextFailureCount(failures, false) }
    assertEquals(5, failures)
    assertEquals(16_000L, CoreBetaReconnectPolicy.delayMillis(failures))
    try { CoreBetaReconnectPolicy.nextFailureCount(failures, false); org.junit.Assert.fail("Unbounded reconnect") }
    catch (_: IllegalStateException) { }
    assertEquals(0, CoreBetaReconnectPolicy.nextFailureCount(failures, true))
    assertEquals(1, CoreBetaReconnectPolicy.nextFailureCount(0, false))
  }
}
