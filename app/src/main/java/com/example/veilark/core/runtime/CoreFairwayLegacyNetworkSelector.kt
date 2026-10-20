package com.example.veilark.core.runtime

/**
 * Passive API29/30 selection policy, not an emulation of the OS best-network score.
 * The physical observer must exclude VPNs in its request; eligibility also means
 * INTERNET, NOT_VPN and not suspended. Publish only after explicit unblocked and
 * immutable link snapshots. Unknown/stale per-network callbacks cannot add entries.
 *
 * A verified physical default hint comes from an independent, current default
 * callback with physical capabilities, never from provisional onAvailable alone.
 * Its still-usable lease may remain pinned after the default becomes opaque/VPN.
 * A sole observed candidate does NOT establish such custody: a second ready
 * candidate invalidates that unverified lease. Callback enumeration is not an
 * atomic inventory; all published leases therefore remain revocable.
 *
 * No Android objects, sockets, timers, probes, radio requests or external callbacks.
 * Capacity/epoch exhaustion closes this owner permanently instead of guessing.
 */
internal class CoreFairwayLegacyNetworkSelector<N : Any>(
  private val maxNetworks: Int = 8,
  initialEpoch: Long = 0,
) {
  internal data class Snapshot<N : Any>(val network: N, val epoch: Long)

  private class Candidate<N : Any>(val network: N) {
    var eligible = false
    var blocked: Boolean? = null
    var link: Any? = null
    fun ready() = eligible && blocked == false && link != null
  }

  init { require(maxNetworks in 1..8 && initialEpoch >= 0) }

  private val candidates = HashMap<N, Candidate<N>>()
  private var defaultHint: N? = null
  private var verifiedPin: N? = null
  private var generation = initialEpoch
  private var published: Snapshot<N>? = null
  private var closed = false

  @Synchronized fun available(network: N) {
    if (closed || candidates.containsKey(network)) return
    if (candidates.size == maxNetworks) {
      closeState()
      return
    }
    candidates[network] = Candidate(network)
    refresh()
  }

  @Synchronized fun capabilities(network: N, eligible: Boolean) {
    if (closed) return
    val candidate = candidates[network] ?: return
    candidate.eligible = eligible
    if (!eligible) forgetVerification(network)
    refresh()
  }

  @Synchronized fun blocked(network: N, blocked: Boolean) {
    if (closed) return
    val candidate = candidates[network] ?: return
    candidate.blocked = blocked
    if (blocked) forgetVerification(network)
    refresh()
  }

  @Synchronized fun link(network: N, immutableFingerprint: Any) {
    if (closed) return
    val candidate = candidates[network] ?: return
    if (candidate.link == immutableFingerprint) return
    candidate.link = immutableFingerprint
    refresh(renewNetwork = network)
  }

  /** Null means the current default is unknown/virtual, not loss of a physical lease. */
  @Synchronized fun verifiedPhysicalDefault(network: N?) {
    if (closed) return
    defaultHint = network
    refresh()
  }

  @Synchronized fun lost(network: N) {
    if (closed) return
    candidates.remove(network)
    forgetVerification(network)
    refresh()
  }

  @Synchronized fun snapshot(): Snapshot<N>? = published

  @Synchronized fun isCurrent(snapshot: Snapshot<N>): Boolean = !closed && published === snapshot

  @Synchronized fun close() = closeState()

  private fun forgetVerification(network: N) {
    if (defaultHint == network) defaultHint = null
    if (verifiedPin == network) verifiedPin = null
  }

  private fun ready(network: N?): N? =
    network?.let { candidates[it]?.takeIf { candidate -> candidate.ready() }?.network }

  private fun soleReady(): N? {
    var sole: N? = null
    for (candidate in candidates.values) {
      if (!candidate.ready()) continue
      if (sole != null) return null
      sole = candidate.network
    }
    return sole
  }

  private fun refresh(renewNetwork: N? = null) {
    val verifiedDefault = ready(defaultHint)
    if (verifiedDefault != null) verifiedPin = verifiedDefault
    val selected = verifiedDefault ?: ready(verifiedPin) ?: soleReady()
    if (selected == null) {
      published = null
      return
    }
    if (published?.network == selected && renewNetwork != selected) return
    if (generation == Long.MAX_VALUE) {
      closeState()
      return
    }
    published = Snapshot(selected, ++generation)
  }

  private fun closeState() {
    closed = true
    candidates.clear()
    defaultHint = null
    verifiedPin = null
    published = null
  }
}
