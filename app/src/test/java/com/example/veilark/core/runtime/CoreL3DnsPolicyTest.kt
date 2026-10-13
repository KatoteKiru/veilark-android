package com.example.veilark.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreL3DnsPolicyTest {
  @Test fun retiredLabStubIsReplacedWithoutChangingStoredInput() {
    val configured = listOf("10.0.2.3")
    assertEquals(EffectiveCoreDns(listOf("1.1.1.1", "77.88.8.8"), 1), CoreL3DnsPolicy.effective(configured))
    assertEquals(listOf("10.0.2.3"), configured)
  }

  @Test fun mixedResolversRetainCustomOrderAndOnlyExactStubIsRemoved() {
    val configured = listOf("77.88.8.8", "10.0.2.3", "10.0.2.30")
    assertEquals(EffectiveCoreDns(listOf("77.88.8.8", "10.0.2.30"), 1), CoreL3DnsPolicy.effective(configured))
    assertEquals(listOf("77.88.8.8", "10.0.2.3", "10.0.2.30"), configured)
  }

  @Test fun customResolverConfigurationIsPreserved() {
    val configured = listOf("9.9.9.9", "1.0.0.1", "192.168.1.1")
    assertEquals(EffectiveCoreDns(configured, 0), CoreL3DnsPolicy.effective(configured))
  }

  @Test(expected = IllegalArgumentException::class)
  fun emptyConfigurationIsRejectedInsteadOfInventingResolvers() {
    CoreL3DnsPolicy.effective(emptyList())
  }
}
