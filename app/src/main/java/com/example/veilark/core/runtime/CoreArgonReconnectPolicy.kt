package com.example.veilark.core.runtime

internal data class CoreArgonRetryDecision(val failureCount: Int, val delayMillis: Long)

/** An owned physical-network rotation is not evidence of a native failure. */
internal object CoreArgonReconnectPolicy {
  fun isPlannedNetworkRotation(
    reachedRunning: Boolean,
    ownStopSucceeded: Boolean,
    callbackFailed: Boolean,
    terminalStatus: Int?,
    releasedOutcome: Int?,
  ): Boolean = reachedRunning && ownStopSucceeded && !callbackFailed &&
    terminalStatus == VeilarkArgonNative.STOPPING && releasedOutcome == 2

  fun next(previousFailures: Int, plannedNetworkRotation: Boolean): CoreArgonRetryDecision {
    require(previousFailures in 0..5)
    if (plannedNetworkRotation) return CoreArgonRetryDecision(previousFailures, 250)
    val failures = CoreBetaReconnectPolicy.nextFailureCount(previousFailures, false)
    return CoreArgonRetryDecision(failures, CoreBetaReconnectPolicy.delayMillis(failures))
  }
}
