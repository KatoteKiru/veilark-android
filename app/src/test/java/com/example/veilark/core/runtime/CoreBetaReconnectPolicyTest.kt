package com.example.veilark.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreBetaReconnectPolicyTest {
  @Test fun onlyAuthenticatedSessionTimeResetsFailureBudget() {
    assertEquals(false, CoreBetaReconnectPolicy.isHealthyRotation(null, 60_000, 10))
    assertEquals(false, CoreBetaReconnectPolicy.isHealthyRotation(59_000, 60_000, 10))
    assertEquals(true, CoreBetaReconnectPolicy.isHealthyRotation(51_000, 60_000, 10))
    assertEquals(false, CoreBetaReconnectPolicy.isHealthyRotation(31_000, 60_000, 1800))
    assertEquals(true, CoreBetaReconnectPolicy.isHealthyRotation(30_000, 60_000, 1800))
  }

  @Test fun reconnectAttemptsAreBoundedAndBackOff() {
    assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), (1..5).map { CoreBetaReconnectPolicy.delayMillis(it) })
  }
  @Test(expected = IllegalArgumentException::class) fun zeroAttemptRejected() { CoreBetaReconnectPolicy.delayMillis(0) }
  @Test(expected = IllegalArgumentException::class) fun unboundedAttemptRejected() { CoreBetaReconnectPolicy.delayMillis(6) }
}
