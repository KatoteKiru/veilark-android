package com.example.veilark.diagnostics

import java.io.IOException

/** Storage failures may disable persistence temporarily, never the VPN process. */
internal class LogPersistenceGuard(
  private val clock: () -> Long = System::nanoTime,
  private val retryDelayNanos: Long = 60_000_000_000L,
) {
  private var failedAt: Long? = null

  @Synchronized
  fun write(onFailure: () -> Unit, operation: () -> Unit): Boolean {
    val now = clock()
    if (failedAt?.let { now - it < retryDelayNanos } == true) return false
    return try {
      operation()
      failedAt = null
      true
    } catch (_: IOException) {
      failedAt = now
      onFailure()
      false
    } catch (_: SecurityException) {
      failedAt = now
      onFailure()
      false
    }
  }
}
