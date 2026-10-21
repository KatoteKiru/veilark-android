package com.example.veilark.core.runtime

/** Independent default-callback evidence only, never a physical inventory.
 * physicalEligible is derived from callback capabilities, not a synchronous lookup.
 * The caller sends snapshot() to the legacy selector; null never certifies a VPN.
 */
internal class CoreFairwayDefaultNetworkHint<N : Any> {
  private var candidate: N? = null
  private var physicalEligible = false
  private var closed = false

  @Synchronized fun available(network: N) {
    if (closed || candidate == network) return
    candidate = network
    physicalEligible = false
  }

  @Synchronized fun capabilities(network: N, physicalEligible: Boolean) {
    if (!closed && candidate == network) this.physicalEligible = physicalEligible
  }

  @Synchronized fun lost(network: N) {
    if (closed || candidate != network) return
    candidate = null
    physicalEligible = false
  }

  /** Inventory loss/block/suspension revokes prior evidence. Only another
   * matching default capabilities callback may independently certify it again.
   */
  @Synchronized fun invalidatePhysical(network: N) {
    if (!closed && candidate == network) physicalEligible = false
  }

  @Synchronized fun snapshot(): N? = if (!closed && physicalEligible) candidate else null

  @Synchronized fun close() {
    closed = true
    candidate = null
    physicalEligible = false
  }
}
