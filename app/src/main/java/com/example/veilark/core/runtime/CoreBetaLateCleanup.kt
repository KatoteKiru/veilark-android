package com.example.veilark.core.runtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Retains cleanup custody after a destroyed service's original coroutine is cancelled. */
internal class CoreBetaLateCleanup(
  private val cleanup: suspend () -> Boolean,
  private val onReleased: () -> Unit,
  private val event: (String) -> Unit,
) {
  private val started = AtomicBoolean(false)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Six existing five-second cleanup windows; never authorizes restart on timeout. */
  fun launchOnce(): Job? {
    if (!started.compareAndSet(false, true)) return null
    event("cleanup_pending")
    return scope.launch {
      try {
        repeat(6) {
          if (cleanup()) {
            onReleased()
            event("cleanup_complete")
            return@launch
          }
        }
        event("cleanup_timeout")
      } catch (_: Exception) {
        // Retain native/manager ownership. Exception text can contain sensitive state.
        event("cleanup_timeout")
      } finally {
        scope.cancel()
      }
    }
  }
}
