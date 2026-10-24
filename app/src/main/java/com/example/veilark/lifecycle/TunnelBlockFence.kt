package com.example.veilark.lifecycle

/**
 * Admission fence for the kill-switch blocking interface.
 *
 * Android hands the package's VPN to whichever service called establish()
 * last. A block established after an engine has been admitted could therefore
 * replace a tunnel the engine has just brought up. Requests are numbered, an
 * admitted engine invalidates every outstanding request, and no new request is
 * granted until the lifecycle owner has confirmed the engine's teardown.
 */
internal class TunnelBlockFence {
  private var lastGeneration = 0L
  private var requestedGeneration = 0L
  private var engineAdmitted = false

  /** Returns the generation to establish, or null while an engine may own the VPN. */
  @Synchronized
  fun request(): Long? {
    if (engineAdmitted) return null
    lastGeneration += 1
    requestedGeneration = lastGeneration
    return lastGeneration
  }

  @Synchronized
  fun cancel() {
    requestedGeneration = 0L
  }

  @Synchronized
  fun hasRequest(): Boolean = requestedGeneration != 0L

  @Synchronized
  fun admitEngine() {
    engineAdmitted = true
    requestedGeneration = 0L
  }

  @Synchronized
  fun engineReleased() {
    engineAdmitted = false
  }

  /**
   * Runs [establish] only for the current request. Holding the monitor across
   * the call keeps a concurrent [admitEngine], and with it the engine start,
   * strictly before or after the block's establish().
   */
  @Synchronized
  fun <T : Any> establishIfCurrent(generation: Long, establish: () -> T?): T? =
    if (generation != 0L && generation == requestedGeneration) establish() else null
}

/** Whether a terminal failure should leave traffic blocked instead of falling back to the bare network. */
internal fun killSwitchShouldBlock(
  enabled: Boolean,
  reason: FailureReason,
  vpnConsentHeld: Boolean,
): Boolean = enabled && vpnConsentHeld && when (reason) {
  FailureReason.EngineFailure, FailureReason.Timeout -> true
  // After a denial or a revoke the user or another VPN app owns the decision.
  FailureReason.PermissionDenied, FailureReason.Revoked -> false
}
