package com.example.veilark.protocol

/** Correlates asynchronous adapter callbacks with one admitted Trust session. */
internal class TrustSessionFence {
  private var activeSessionId: Long? = null
  private var connected = false

  @Synchronized
  fun begin(sessionId: Long) {
    require(sessionId > 0L) { "sessionId must be positive" }
    activeSessionId = sessionId
    connected = false
  }

  @Synchronized
  fun accepts(sessionId: Long): Boolean = activeSessionId == sessionId

  @Synchronized
  fun acceptConnected(sessionId: Long): Boolean {
    if (activeSessionId != sessionId || connected) return false
    connected = true
    return true
  }

  @Synchronized
  fun isConnected(sessionId: Long): Boolean =
    activeSessionId == sessionId && connected

  /** Returns true only for the first terminalization of the active session. */
  @Synchronized
  fun terminalize(sessionId: Long): Boolean {
    if (activeSessionId != sessionId) return false
    activeSessionId = null
    connected = false
    return true
  }
}
