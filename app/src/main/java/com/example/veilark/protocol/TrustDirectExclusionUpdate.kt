package com.example.veilark.protocol

/**
 * Runs a native direct-route update outside the lifecycle monitor.
 *
 * Native exclusion updates can take noticeable time for a national Geo-IP
 * dataset. The caller supplies both fences: one before the native call and one
 * before publishing its result. This permits Stop/Switch to invalidate a late
 * result without waiting on the manager monitor.
 */
internal class TrustDirectExclusionUpdate(
  private val isCurrent: () -> Boolean,
  private val update: (List<String>) -> Boolean,
  private val publish: (Boolean) -> Unit,
) {
  fun run(directCidrs: List<String>) {
    if (!isCurrent()) return
    val applied = runCatching { update(directCidrs) }.getOrDefault(false)
    if (isCurrent()) publish(applied)
  }
}
