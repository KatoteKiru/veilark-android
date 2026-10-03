package com.example.veilark.protocol

/** Correlates asynchronous adapter callbacks with one admitted Trust session. */
internal class TrustSessionFence {
  private var activeSessionId: Long? = null
  private var connected = false
  private var stopping = false

  @Synchronized
  fun begin(sessionId: Long) {
    require(sessionId > 0L) { "sessionId must be positive" }
    check(activeSessionId == null) { "Previous Trust session has not completed teardown" }
    activeSessionId = sessionId
    connected = false
    stopping = false
  }

  @Synchronized
  fun accepts(sessionId: Long): Boolean = activeSessionId == sessionId

  @Synchronized
  fun acceptConnected(sessionId: Long): Boolean {
    if (activeSessionId != sessionId || connected || stopping) return false
    connected = true
    return true
  }

  @Synchronized
  fun isConnected(sessionId: Long): Boolean =
    activeSessionId == sessionId && connected && !stopping

  /** A stop request fences non-terminal callbacks but does not release ownership. */
  @Synchronized
  fun requestStop(sessionId: Long): Boolean {
    if (activeSessionId != sessionId) return false
    stopping = true
    return true
  }

  /** Returns true only for the first terminalization of the active session. */
  @Synchronized
  fun terminalize(sessionId: Long): Boolean {
    if (activeSessionId != sessionId) return false
    activeSessionId = null
    connected = false
    stopping = false
    return true
  }
}
