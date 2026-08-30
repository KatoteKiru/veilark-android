package com.example.veilark.protocol

import android.content.Context
import com.adguard.trusttunnel.AppNotifier
import com.adguard.trusttunnel.VpnService
import com.adguard.trusttunnel.VpnState
import com.example.veilark.R
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.lifecycle.AndroidTunnelLifecycleOwner
import com.example.veilark.lifecycle.LifecycleAttempt
import com.example.veilark.NativeRuntimeState
import com.example.veilark.vpn.ConnectionState
import com.example.veilark.vpn.EndpointLatencyProbe
import com.example.veilark.vpn.ManualLatencyPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.system.measureTimeMillis

object TrustTunnelManager : AppNotifier {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutableState = MutableStateFlow(ConnectionState.Disconnected)
  val state = mutableState.asStateFlow()
  private val mutableFailureMessage = MutableStateFlow<String?>(null)
  val failureMessage = mutableFailureMessage.asStateFlow()
  private val mutableTransport = MutableStateFlow<String?>(null)
  val transport = mutableTransport.asStateFlow()
  private val mutableLatencies = MutableStateFlow<Map<String, Int>>(emptyMap())
  val latencies = mutableLatencies.asStateFlow()
  private val mutableLatencyChecking = MutableStateFlow(false)
  val latencyChecking = mutableLatencyChecking.asStateFlow()
  private val mutableStopped = MutableStateFlow(true)
  internal val stopped = mutableStopped.asStateFlow()
  private var latencyJob: Job? = null
  private var latencyGeneration = 0L
  private val sessionFence = TrustSessionFence()
  @Volatile
  private var connectionRequested = false
  @Volatile
  private var hasConnected = false
  @Volatile
  private var probeGeneration = 0
  private val networkManagerLifecycle = TrustNetworkManagerLifecycle()
  @Volatile
  private var activeLifecycleAttempt: LifecycleAttempt? = null
  @Volatile
  private var pendingDirectCidrs: List<String> = emptyList()
  private lateinit var appContext: Context

  fun initialize(context: Context) {
    appContext = context.applicationContext
    // Remove routing state written by the short-lived experimental adapter in 0.7.7.
    context.getSharedPreferences(
      "trusttunnel_application_routing",
      Context.MODE_PRIVATE,
    ).edit().clear().apply()
    val queryLog = File(context.cacheDir, "trusttunnel/query-log.dat")
    queryLog.parentFile?.mkdirs()
    VpnService.setAppNotifier(
      queryLog,
      this,
    )
  }

  @Synchronized
  fun start(context: Context, config: String) {
    NativeRuntimeState.requireTrustTunnel()
    check(VpnServiceConfigValidator.isValid(config)) {
      context.getString(R.string.trust_invalid_configuration)
    }
    AndroidTunnelLifecycleOwner.startTrustTunnel(context, config)
  }

  internal fun startEngine(context: Context, config: String, attempt: LifecycleAttempt) {
    NativeRuntimeState.requireTrustTunnel()
    val startupConfig = TrustTunnelGeoRouting.startupConfig(config)
    val directCidrs = TrustTunnelGeoRouting.currentDirectCidrs(context)
    check(VpnServiceConfigValidator.isValid(startupConfig)) {
      context.getString(R.string.trust_invalid_configuration)
    }
    val generation = synchronized(this) {
      connectionRequested = true
      activeLifecycleAttempt = attempt
      sessionFence.begin(attempt.attemptId)
      pendingDirectCidrs = directCidrs
      mutableStopped.value = false
      hasConnected = false
      mutableFailureMessage.value = null
      mutableTransport.value = null
      mutableState.value = ConnectionState.Connecting
      cancelLatencyRefresh()
      ++probeGeneration
    }
    TechnicalLogStore.info(
      "TRUST",
      "Starting tunnel; deferred direct routes=${directCidrs.size}",
    )
    EndpointLatencyProbe.stop()
    try {
      ensureNetworkManager(context.applicationContext)
      check(VpnService.start(context, startupConfig, attempt.attemptId)) {
        context.getString(R.string.vpn_start_failed)
      }
    } catch (failure: Throwable) {
      failAndStop(
        context = context.applicationContext,
        sessionId = attempt.attemptId,
        message = failure.message ?: context.getString(R.string.vpn_start_failed),
        logMessage = "Android rejected the TrustTunnel service start",
      )
      throw failure
    }
    scheduleConnectionTimeout(context.applicationContext, generation, attempt.attemptId)
  }

  private fun scheduleConnectionTimeout(context: Context, generation: Int, sessionId: Long) {
    scope.launch {
      delay(CONNECTION_TIMEOUT_MS)
      handleConnectionTimeout(context, generation, sessionId)
    }
  }

  private fun handleConnectionTimeout(context: Context, generation: Int, sessionId: Long) {
    val stillPending = synchronized(this) {
      generation == probeGeneration &&
        connectionRequested &&
        mutableState.value == ConnectionState.Connecting &&
        sessionFence.accepts(sessionId)
    }
    if (!stillPending) return
    failAndStop(
      context = context,
      sessionId = sessionId,
      message = context.getString(R.string.trust_connection_timeout),
      logMessage = "Connection timed out",
    )
  }

  fun stop(context: Context) {
    mutableFailureMessage.value = null
    AndroidTunnelLifecycleOwner.stop(context)
  }

  internal fun stopEngine(context: Context, attempt: LifecycleAttempt? = null) {
    var stopSessionId: Long? = null
    val alreadyStopped = synchronized(this) {
      val active = activeLifecycleAttempt
      if (attempt != null && active != attempt) return
      connectionRequested = false
      hasConnected = false
      pendingDirectCidrs = emptyList()
      probeGeneration += 1
      mutableTransport.value = null
      cancelLatencyRefresh()
      if (mutableState.value == ConnectionState.Disconnected || active == null) {
        active?.let { sessionFence.terminalize(it.attemptId) }
        activeLifecycleAttempt = null
        mutableStopped.value = true
        true
      } else {
        stopSessionId = active.attemptId
        false
      }
    }
    if (alreadyStopped) {
      runCatching { stopNetworkManager() }
      return
    }
    val sessionId = stopSessionId ?: return
    if (!VpnService.stop(context, sessionId)) {
      terminalizeDisconnected(sessionId)
    }
    TechnicalLogStore.info("TRUST", "Tunnel stopped by user")
  }

  @Synchronized
  fun refreshLatencies(profiles: List<TrustTunnelCatalogEntry>) {
    latencyJob?.cancel()
    val generation = ++latencyGeneration
    mutableLatencyChecking.value = true
    latencyJob = scope.launch {
      try {
        val targets = ManualLatencyPolicy.boundedTargets(profiles)
        val measured = withTimeoutOrNull(ManualLatencyPolicy.DEADLINE_MS) {
          coroutineScope {
            val semaphore = Semaphore(LATENCY_PARALLELISM)
            targets.map { profile ->
              async {
                semaphore.withPermit {
                  measureEndpointLatency(profile.config)?.let { profile.id to it }
                }
              }
            }.awaitAll().filterNotNull().toMap()
          }
        }.orEmpty()
        if (generation == latencyGeneration) mutableLatencies.value = measured
      } finally {
        synchronized(this@TrustTunnelManager) {
          if (generation == latencyGeneration) {
            mutableLatencyChecking.value = false
            latencyJob = null
          }
        }
      }
    }
  }

  @Synchronized
  private fun cancelLatencyRefresh() {
    latencyGeneration += 1L
    latencyJob?.cancel()
    latencyJob = null
    mutableLatencyChecking.value = false
  }

  override fun onStateChanged(state: Int, sessionId: Long) {
    val nativeState = VpnState.getByCode(state)
    when (nativeState) {
      VpnState.DISCONNECTED -> terminalizeDisconnected(sessionId)
      VpnState.CONNECTED -> handleConnected(sessionId)
      VpnState.CONNECTING -> synchronized(this) {
        if (connectionRequested && sessionFence.accepts(sessionId)) {
          mutableState.value = ConnectionState.Connecting
        }
      }
      VpnState.WAITING_RECOVERY,
      VpnState.RECOVERING,
      VpnState.WAITING_FOR_NETWORK,
      -> {
        val accepted = synchronized(this) {
          if (!sessionFence.accepts(sessionId)) false
          else {
            mutableState.value = trustRecoveryUiState(hasConnected, connectionRequested)
            true
          }
        }
        if (accepted) {
          TechnicalLogStore.warning("TRUST", "Physical network transition: ${nativeState.name}")
        }
      }
    }
  }

  private fun handleConnected(sessionId: Long) {
    val directCidrs = synchronized(this) {
      if (
        !connectionRequested ||
        activeLifecycleAttempt?.attemptId != sessionId ||
        !sessionFence.acceptConnected(sessionId)
      ) null else pendingDirectCidrs
    }
    if (directCidrs == null) {
      TechnicalLogStore.warning("TRUST", "Ignored a stale or duplicate CONNECTED event")
      return
    }
    val routingApplied = directCidrs.isEmpty() || VpnService.updateExclusions(directCidrs)
    if (!routingApplied) {
      failAndStop(
        context = appContext,
        sessionId = sessionId,
        message = appContext.getString(R.string.trust_connection_failed),
        logMessage = "Failed to apply runtime routing policy",
      )
      return
    }
    val accepted = synchronized(this) {
      if (!connectionRequested || !sessionFence.isConnected(sessionId)) false
      else {
        hasConnected = true
        mutableState.value = ConnectionState.Connected
        true
      }
    }
    if (accepted) {
      TechnicalLogStore.info(
        "TRUST",
        "Tunnel connected; runtime direct routes=${directCidrs.size}",
      )
    }
  }

  private fun terminalizeDisconnected(sessionId: Long) {
    val terminalState = synchronized(this) {
      if (!sessionFence.terminalize(sessionId)) return
      val requested = connectionRequested
      connectionRequested = false
      hasConnected = false
      activeLifecycleAttempt = null
      pendingDirectCidrs = emptyList()
      mutableStopped.value = true
      cancelLatencyRefresh()
      when {
        requested -> {
          mutableFailureMessage.value = appContext.getString(R.string.trust_connection_failed)
          ConnectionState.Failed
        }
        mutableFailureMessage.value != null -> ConnectionState.Failed
        else -> ConnectionState.Disconnected
      }.also { mutableState.value = it }
    }
    runCatching { stopNetworkManager() }
    if (terminalState == ConnectionState.Failed) {
      TechnicalLogStore.error("TRUST", "Core ended the connection with an error")
    }
  }

  private fun failAndStop(
    context: Context,
    sessionId: Long,
    message: String,
    logMessage: String,
  ): Boolean {
    val terminalized = synchronized(this) {
      if (!sessionFence.terminalize(sessionId)) return false
      connectionRequested = false
      hasConnected = false
      activeLifecycleAttempt = null
      pendingDirectCidrs = emptyList()
      probeGeneration += 1
      mutableTransport.value = null
      mutableFailureMessage.value = message
      mutableState.value = ConnectionState.Failed
      mutableStopped.value = true
      cancelLatencyRefresh()
      true
    }
    if (!terminalized) return false
    runCatching { VpnService.stop(context, sessionId) }
    runCatching { stopNetworkManager() }
    TechnicalLogStore.error("TRUST", logMessage)
    return true
  }

  override fun onConnectionInfo(info: String) {
    val normalized = info.lowercase()
    val transport = when {
      "http3" in normalized || "quic" in normalized -> "H3"
      "http2" in normalized -> "H2"
      else -> mutableTransport.value
    }
    if (transport != mutableTransport.value) {
      mutableTransport.value = transport
      TechnicalLogStore.info("TRUST", "Negotiated transport ${transport ?: "unknown"}")
    }
  }

  private fun ensureNetworkManager(context: Context) {
    networkManagerLifecycle.ensure {
      VpnService.startNetworkManager(context)
      TechnicalLogStore.info("TRUST", "Physical network monitor started on demand")
    }
  }

  private fun stopNetworkManager() {
    networkManagerLifecycle.stop {
      VpnService.stopNetworkManager()
      TechnicalLogStore.info("TRUST", "Physical network monitor stopped")
    }
  }

  private suspend fun measureEndpointLatency(config: String): Int? {
    val addresses = Regex("""(?m)^\s*addresses\s*=\s*\[(.*)]\s*$""")
      .find(config)
      ?.groupValues
      ?.get(1)
      ?.let { values ->
        Regex(""""([^"]+)"""").findAll(values).map { it.groupValues[1] }.toList()
      }
      .orEmpty()
    var minimum: Int? = null
    for (address in addresses) {
      val latency = measureAddress(address) ?: continue
      minimum = minimum?.coerceAtMost(latency) ?: latency
    }
    return minimum
  }

  private suspend fun measureAddress(address: String): Int? {
    val endpoint = parseAddress(address) ?: return null
    return suspendCancellableCoroutine { continuation ->
      val socket = Socket()
      continuation.invokeOnCancellation { runCatching { socket.close() } }
      var connected = false
      val elapsed = measureTimeMillis {
        connected = runCatching {
          socket.connect(InetSocketAddress(endpoint.first, endpoint.second), 2_000)
          true
        }.getOrDefault(false)
      }
      runCatching { socket.close() }
      if (continuation.isActive) {
        continuation.resume(elapsed.toInt().takeIf { connected })
      }
    }
  }

  private fun parseAddress(address: String): Pair<String, Int>? {
    val value = address.trim()
    val ipv6 = Regex("""^\[([^]]+)]:(\d+)$""").matchEntire(value)
    if (ipv6 != null) {
      return ipv6.groupValues[1] to
        ipv6.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 }
          .let { it ?: return null }
    }
    val separator = value.lastIndexOf(':')
    if (separator <= 0) return null
    val host = value.substring(0, separator)
    val port = value.substring(separator + 1).toIntOrNull()?.takeIf { it in 1..65535 }
      ?: return null
    return host to port
  }
}

internal fun trustRecoveryUiState(
  wasConnected: Boolean,
  connectionRequested: Boolean,
): ConnectionState =
  when {
    !connectionRequested -> ConnectionState.Disconnected
    wasConnected -> ConnectionState.Connected
    else -> ConnectionState.Connecting
  }

private object VpnServiceConfigValidator {
  fun isValid(config: String): Boolean =
    com.adguard.trusttunnel.VpnServiceConfig.parseToml(config) != null
}

private const val CONNECTION_TIMEOUT_MS = 45_000L
private const val LATENCY_PARALLELISM = 6
