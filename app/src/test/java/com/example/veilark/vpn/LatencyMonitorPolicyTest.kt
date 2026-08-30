package com.example.veilark.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyMonitorPolicyTest {
  @Test
  fun callbacksRequireCurrentRunningGeneration() {
    assertTrue(latencyCallbackAccepted(4L, 4L, shouldRun = true))
    assertFalse(latencyCallbackAccepted(3L, 4L, shouldRun = true))
    assertFalse(latencyCallbackAccepted(4L, 4L, shouldRun = false))
  }
}
