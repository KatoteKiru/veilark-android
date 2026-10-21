package com.example.veilark.core.runtime

import org.junit.Assert.*
import org.junit.Test

/** Two callback-source evidence checks only, not Android/API29 acceptance. */
class CoreFairwayDefaultNetworkHintTest {
  @Test fun rawAvailableAndVirtualOrStaleCapabilitiesNeverCertifyPhysicalDefault() {
    val hint = CoreFairwayDefaultNetworkHint<String>()
    hint.capabilities("A", true)
    assertNull(hint.snapshot())
    hint.available("A")
    assertNull(hint.snapshot())
    hint.capabilities("A", true)
    assertEquals("A", hint.snapshot())
    hint.available("VPN")
    assertNull(hint.snapshot())
    hint.capabilities("A", true)
    hint.lost("A")
    assertNull(hint.snapshot())
    hint.capabilities("VPN", false)
    assertNull(hint.snapshot())
    hint.available("B")
    hint.capabilities("B", true)
    hint.available("B")
    hint.lost("VPN")
    assertEquals("B", hint.snapshot())
  }

  @Test fun inventoryRevocationNeedsNewDefaultCapabilitiesAndCloseIsTerminal() {
    val hint = CoreFairwayDefaultNetworkHint<String>()
    hint.available("A")
    hint.capabilities("A", true)
    hint.invalidatePhysical("other")
    assertEquals("A", hint.snapshot())
    hint.invalidatePhysical("A")
    hint.available("A")
    hint.lost("old")
    hint.capabilities("old", true)
    assertNull(hint.snapshot())
    hint.capabilities("A", true)
    assertEquals("A", hint.snapshot())
    hint.close()
    hint.available("B")
    hint.capabilities("B", true)
    assertNull(hint.snapshot())
  }

  @Test fun unrelatedDefaultCallbackCannotResurrectRevokedPhysicalCustody() {
    val selector = CoreFairwayLegacyNetworkSelector<String>()
    val hint = CoreFairwayDefaultNetworkHint<String>()
    fun ready(network: String) {
      selector.available(network)
      selector.capabilities(network, true)
      selector.blocked(network, false)
      selector.link(network, network)
    }
    fun forwardHint() = selector.verifiedPhysicalDefault(hint.snapshot())
    ready("A")
    hint.available("A"); hint.capabilities("A", true); forwardHint()
    val old = selector.snapshot()!!
    ready("B")
    assertSame(old, selector.snapshot())
    hint.invalidatePhysical("A")
    selector.blocked("A", true)
    selector.blocked("A", false)
    assertNull(selector.snapshot())
    hint.lost("unrelated"); forwardHint()
    assertNull(selector.snapshot())
    assertFalse(selector.isCurrent(old))
    hint.capabilities("A", true); forwardHint()
    assertEquals("A", selector.snapshot()!!.network)
    assertTrue(selector.snapshot()!!.epoch > old.epoch)
  }
}
