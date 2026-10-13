package com.example.veilark.core.runtime

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.veilark.BuildConfig
import com.example.veilark.lifecycle.AndroidTunnelLifecycleOwner
import com.example.veilark.lifecycle.LifecycleAttempt
import com.example.veilark.profile.CoreBetaProfileStore
import com.example.veilark.vpn.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Import and selection are separate from this explicit user-requested start. */
object CoreBetaManager {
  private val mutableState = MutableStateFlow(ConnectionState.Disconnected)
  val state = mutableState.asStateFlow()
  private val mutableFailure = MutableStateFlow<String?>(null)
  val failureMessage = mutableFailure.asStateFlow()
  private val mutableStopped = MutableStateFlow(true)
  val stopped = mutableStopped.asStateFlow()
  @Volatile private var activeAttempt: LifecycleAttempt? = null
  private var serviceAttached = false

  fun isAvailable(): Boolean = BuildConfig.CORE_BETA_RUNTIME_BUNDLED &&
    Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

  fun start(context: Context) {
    AndroidTunnelLifecycleOwner.startCoreBeta(context)
  }

  @Synchronized internal fun startEngine(context: Context, attempt: LifecycleAttempt) {
    check(isAvailable()) { "Veilark Core beta is unavailable in this build or on this device." }
    check(VpnService.prepare(context) == null) { "VPN permission is required." }
    check(CoreBetaProfileStore.exists(context)) { "Import a Veilark Core beta profile first." }
    check(mutableStopped.value) { "Previous Core beta session has not stopped." }
    activeAttempt = attempt
    serviceAttached = false
    publish(attempt, ConnectionState.Connecting)
    mutableStopped.value = false
    try {
      ContextCompat.startForegroundService(context, Intent(context, CoreBetaVpnService::class.java)
        .putExtra("attempt", attempt.attemptId).putExtra("epoch", attempt.engineEpoch))
    } catch (failure: Exception) {
      publish(attempt, ConnectionState.Failed, "Android could not start Veilark Core beta.")
      released(attempt)
      throw IllegalStateException("Android could not start Veilark Core beta.")
    }
  }

  fun stop(context: Context) {
    // Bind the immediate service stop to the current lease. A later start must
    // not be stopped by this request after the coordinator command is queued.
    val current = synchronized(this) { activeAttempt }
    requestCoreStop(
      current,
      submitOwnerStop = { AndroidTunnelLifecycleOwner.stop(context) },
      stopCapturedService = { stopEngine(context, it) },
    )
  }

  @Synchronized internal fun stopEngine(context: Context, attempt: LifecycleAttempt? = null) {
    val current = activeAttempt ?: return
    if (attempt != null && current != attempt) return
    com.example.veilark.diagnostics.TechnicalLogStore.info("CORE", "stop_requested")
    if (serviceAttached) {
      // stopService alone cannot stop a still-bound VPN service's worker. Send
      // an explicit, fenced command to cancel it while the service is alive.
      context.startService(Intent(context, CoreBetaVpnService::class.java)
        .setAction(CoreBetaVpnService.ACTION_STOP)
        .putExtra("attempt", current.attemptId).putExtra("epoch", current.engineEpoch))
    } else {
      context.stopService(Intent(context, CoreBetaVpnService::class.java))
    }
    // A queued service start cannot own JNI/TUN until attachAttempt succeeds.
    // Invalidate its token even when Android reports a pending service existed.
    if (!serviceAttached) released(current)
  }

  @Synchronized internal fun attachAttempt(id: Long, epoch: Long): LifecycleAttempt? = activeAttempt?.takeIf {
    it.attemptId == id && it.engineEpoch == epoch && !serviceAttached
  }?.also {
    serviceAttached = true
  }

  @Synchronized internal fun hasPendingStart(): Boolean = activeAttempt != null && !serviceAttached

  @Synchronized internal fun publish(attempt: LifecycleAttempt, state: ConnectionState, failure: String? = null) {
    if (activeAttempt != attempt) return
    mutableFailure.value = failure
    mutableState.value = state
  }

  @Synchronized internal fun released(attempt: LifecycleAttempt) {
    if (activeAttempt != attempt) return
    mutableStopped.value = true
    if (mutableState.value != ConnectionState.Failed) publish(attempt, ConnectionState.Disconnected)
    activeAttempt = null
    serviceAttached = false
  }
}

/** Request service cancellation without waiting behind the owner's startup/stop mutex.
 * The owner still performs the stop fence and never admits an unreleased replacement. */
internal fun requestCoreStop(
  captured: LifecycleAttempt?,
  submitOwnerStop: () -> Unit,
  stopCapturedService: (LifecycleAttempt) -> Unit,
) {
  submitOwnerStop()
  captured?.let(stopCapturedService)
}
