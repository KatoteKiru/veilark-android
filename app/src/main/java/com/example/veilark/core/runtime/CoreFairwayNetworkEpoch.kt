package com.example.veilark.core.runtime

/** Owns one app-default network generation. This epoch is unrelated to engineEpoch.
 * Callers supply immutable link fingerprints and serialize Android callbacks through these methods.
 * No socket IO, timers or native callbacks run while this object's monitor is held.
 */
internal class CoreFairwayNetworkEpoch<N : Any>(initialEpoch: Long = 0) {
  internal data class Snapshot<N : Any>(val network: N, val epoch: Long)

  init { require(initialEpoch >= 0) }

  private var candidate: N? = null
  private var eligible = false
  private var blocked: Boolean? = null
  private var linkFingerprint: Any? = null
  private var generation = initialEpoch
  private var published: Snapshot<N>? = null
  private var closed = false

  @Synchronized fun available(network: N) {
    if (closed || candidate == network) return
    candidate = network
    eligible = false
    blocked = null
    linkFingerprint = null
    published = null
  }

  @Synchronized fun capabilities(network: N, eligible: Boolean) {
    if (closed || candidate != network || this.eligible == eligible) return
    this.eligible = eligible
    refresh()
  }

  @Synchronized fun blocked(network: N, blocked: Boolean) {
    if (closed || candidate != network || this.blocked == blocked) return
    this.blocked = blocked
    refresh()
  }

  @Synchronized fun link(network: N, immutableFingerprint: Any) {
    if (closed || candidate != network || linkFingerprint == immutableFingerprint) return
    linkFingerprint = immutableFingerprint
    // Even a currently usable network needs a fresh generation after a link change.
    published = null
    refresh()
  }

  @Synchronized fun lost(network: N) {
    if (closed || candidate != network) return
    clearCandidate()
  }

  @Synchronized fun close() {
    closed = true
    clearCandidate()
  }

  @Synchronized fun snapshot(): Snapshot<N>? = published

  @Synchronized fun isCurrent(snapshot: Snapshot<N>): Boolean =
    !closed && published === snapshot

  private fun clearCandidate() {
    candidate = null
    eligible = false
    blocked = null
    linkFingerprint = null
    published = null
  }

  private fun refresh() {
    if (!eligible || blocked != false || linkFingerprint == null) {
      published = null
      return
    }
    if (published != null) return
    val network = candidate ?: return
    if (generation == Long.MAX_VALUE) {
      closed = true
      clearCandidate()
      return
    }
    published = Snapshot(network, ++generation)
  }
}
