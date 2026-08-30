package com.example.veilark.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualLatencyPolicyTest {
  @Test
  fun boundsLargeSubscriptionsWithoutReorderingTargets() {
    val targets = (0 until ManualLatencyPolicy.MAX_TARGETS + 20).toList()

    assertEquals(
      (0 until ManualLatencyPolicy.MAX_TARGETS).toList(),
      ManualLatencyPolicy.boundedTargets(targets),
    )
  }

  @Test
  fun keepsSmallTargetListsUnchanged() {
    assertEquals(listOf("a", "b"), ManualLatencyPolicy.boundedTargets(listOf("a", "b")))
  }
}
