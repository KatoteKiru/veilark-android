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
import com.example.veilark.profile.EnrollmentV2Store
import com.example.veilark.profile.CoreProfileKind
import com.example.veilark.R
import com.example.veilark.vpn.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

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
  private val startCommands = CoreBetaStartCommands()

  fun isAvailable(): Boolean = BuildConfig.CORE_BETA_RUNTIME_BUNDLED &&
    coreRuntimeAbiSupported(Build.SUPPORTED_ABIS.toList())

  suspend fun start(context: Context) {
    val command = startCommands.begin()
    startCommands.ifCurrent(command) {
      mutableFailure.value = null
      mutableState.value = ConnectionState.Connecting
    }
    try {
      requireProfileSupportedAsync(context)
      startCommands.ifCurrent(command) { AndroidTunnelLifecycleOwner.startCoreBeta(context, command) }
    } catch (failure: Throwable) {
      startCommands.ifCurrent(command) {
        mutableState.value = if (failure is CancellationException) ConnectionState.Disconnected else ConnectionState.Failed
        mutableFailure.value = if (failure is CancellationException) null else context.getString(R.string.core_beta_unavailable)
      }
      throw failure
    }
  }

  suspend fun requireProfileSupportedAsync(context: Context) = withContext(Dispatchers.IO) {
    requireProfileSupported(context)
  }

  suspend fun deleteProfileWhenStopped(context: Context) = withContext(Dispatchers.IO) {
    startCommands.pauseWhen { mutableStopped.value }
    try {
      mutableState.value = ConnectionState.Disconnected
      mutableFailure.value = null
      CoreProfileStore.deleteSelected(context)
    }
    finally { startCommands.resume() }
  }

  suspend fun importProfileWhenStopped(context: Context, payload: ByteArray) = withContext(Dispatchers.IO) {
    startCommands.pauseWhen { mutableStopped.value }
    try {
      mutableState.value = ConnectionState.Disconnected
      mutableFailure.value = null
      CoreProfileStore.import(context, payload)
    }
    finally { startCommands.resume() }
  }

  suspend fun importEnrollmentWhenStopped(context: Context, link: String) = withContext(Dispatchers.IO) {
    startCommands.pauseWhen { mutableStopped.value }
    try {
      mutableState.value = ConnectionState.Disconnected
      mutableFailure.value = null
      EnrollmentV2Store.importSelectedLink(context, link)
    } finally { startCommands.resume() }
  }

  /** Recheck before owner handover. Service dispatches each kind to its own JNI. */
  fun requireProfileSupported(context: Context): Unit = synchronized(CoreProfileStore) {
    // Validate decrypted data before any caller stops an existing VPN. File
    // existence alone cannot detect a missing Keystore key or corrupt profile.
    val metadata = CoreProfileStore.loadMetadata(context)
    check(isAvailable()) { context.getString(R.string.core_beta_unavailable) }
    val snapshot = context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE).all
      .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
    CoreConnectionPreflight.prepareIntegrated(metadata.kind, metadata.dnsIpv4, snapshot,
      Build.VERSION.SDK_INT, context.packageName,
      runtimeAvailable = { kind ->
        val available = when (kind) {
          CoreProfileKind.LEGACY_V1 -> VeilarkCoreRuntimeNative.loadCandidate()
          CoreProfileKind.ARGON_V1 -> VeilarkArgonNative.isProfileAbiAvailable(
            checkNotNull(metadata.argonHandshakeProfile))
        }
        check(available) { context.getString(R.string.core_beta_unavailable) }
        true
      },
      installed = { app ->
        try { context.packageManager.getApplicationInfo(app, 0); true }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
      },
      commonFairway = CoreFairwayIntegration.ENABLED,
      readAsset = { path -> context.assets.open(path).use { it.readBytes() } },
    )
    Unit
  }

  internal fun startEngine(context: Context, attempt: LifecycleAttempt, command: Long) {
    check(isAvailable()) { "Veilark Core is unavailable in this build or on this device." }
    check(VpnService.prepare(context) == null) { "VPN permission is required." }
    requireProfileSupported(context)
    check(startCommands.ifCurrent(command) {
      synchronized(this) {
        check(mutableStopped.value) { "Previous Core beta session has not stopped." }
        activeAttempt = attempt
        serviceAttached = false
        publish(attempt, ConnectionState.Connecting)
        mutableStopped.value = false
      }
    }) { "Core start was superseded." }
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
      submitOwnerStop = { startCommands.stop {
        AndroidTunnelLifecycleOwner.stop(context)
        if (current == null) {
          mutableState.value = ConnectionState.Disconnected
          mutableFailure.value = null
        }
      } },
      stopCapturedService = { stopEngine(context, it) },
    )
  }

  internal fun stopEngine(context: Context, attempt: LifecycleAttempt? = null) {
    val (current, attached) = synchronized(this) {
      val current = activeAttempt ?: return
      if (attempt != null && current != attempt) return
      if (!serviceAttached) {
        // Invalidate before a queued onStartCommand can attach. Do not issue a
        // global stopService after opening admission to a replacement lease.
        released(current)
        return
      }
      current to serviceAttached
    }
    com.example.veilark.diagnostics.TechnicalLogStore.info("CORE", "${context.getString(R.string.core_beta_title)} stop_requested")
    if (attached) {
      // stopService alone cannot stop a still-bound VPN service's worker. Send
      // an explicit, fenced command to cancel it while the service is alive.
      context.startService(Intent(context, CoreBetaVpnService::class.java)
        .setAction(CoreBetaVpnService.ACTION_STOP)
        .putExtra("attempt", current.attemptId).putExtra("epoch", current.engineEpoch))
    }
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
