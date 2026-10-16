package com.example.veilark.core.runtime

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.veilark.BuildConfig
import com.example.veilark.lifecycle.AndroidTunnelLifecycleOwner
import com.example.veilark.lifecycle.LifecycleAttempt
import com.example.veilark.profile.CoreProfileStore
import com.example.veilark.profile.CoreProfileKind
import com.example.veilark.R
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
    requireProfileSupported(context)
    AndroidTunnelLifecycleOwner.startCoreBeta(context)
  }

  /** Recheck before owner handover. Service dispatches each kind to its own JNI. */
  fun requireProfileSupported(context: Context): Unit = synchronized(CoreProfileStore) {
    // Validate decrypted data before any caller stops an existing VPN. File
    // existence alone cannot detect a missing Keystore key or corrupt profile.
    val metadata = CoreProfileStore.loadMetadata(context)
    check(isAvailable()) { context.getString(R.string.core_beta_unavailable) }
    val snapshot = context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE).all
      .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
    CoreConnectionPreflight.prepare(metadata.kind, metadata.dnsIpv4, snapshot,
      Build.VERSION.SDK_INT, context.packageName,
      runtimeAvailable = { kind ->
        val available = when (kind) {
          CoreProfileKind.LEGACY_V1 -> VeilarkCoreRuntimeNative.loadCandidate()
          CoreProfileKind.ARGON_V1 -> VeilarkArgonNative.isAbiAvailable()
        }
        check(available) { context.getString(R.string.core_beta_unavailable) }
        true
      },
      installed = { app ->
        try { context.packageManager.getApplicationInfo(app, 0); true }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
      },
      readAsset = { path -> context.assets.open(path).use { it.readBytes() } },
    )
    Unit
  }

  @Synchronized internal fun startEngine(context: Context, attempt: LifecycleAttempt) {
    check(isAvailable()) { "Veilark Core is unavailable in this build or on this device." }
    check(VpnService.prepare(context) == null) { "VPN permission is required." }
    requireProfileSupported(context)
    check(mutableStopped.value) { "Previous Core beta session has not stopped." }
    activeAttempt = attempt
    serviceAttached = false
    publish(attempt, ConnectionState.Connecting)
    mutableStopped.value = false
    try {
      ContextCompat.startForegroundService(context, Intent(context, CoreBetaVpnService::class.java)
        .putExtra("attempt", attempt.attemptId).putExtra("epoch", attempt.engineEpoch))
    } catch (failure: Exception) {
      publish(attempt, ConnectionState.Failed, "Android could not start Veilark Core.")
      released(attempt)
      throw IllegalStateException("Android could not start Veilark Core.")
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
    com.example.veilark.diagnostics.TechnicalLogStore.info("CORE", "${context.getString(R.string.core_beta_title)} stop_requested")
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
