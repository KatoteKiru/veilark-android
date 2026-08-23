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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
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
  @Volatile
  private var connectionRequested = false
  @Volatile
  private var hasConnected = false
  @Volatile
  private var probeGeneration = 0
  @Volatile
  private var networkManagerStarted = false
  @Volatile
  private var activeLifecycleAttempt: LifecycleAttempt? = null
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

  @Synchronized
  internal fun startEngine(context: Context, config: String, attempt: LifecycleAttempt) {
    NativeRuntimeState.requireTrustTunnel()
    check(VpnServiceConfigValidator.isValid(config)) {
      context.getString(R.string.trust_invalid_configuration)
    }
    connectionRequested = true
    activeLifecycleAttempt = attempt
    mutableStopped.value = false
    hasConnected = false
    val generation = ++probeGeneration
    mutableFailureMessage.value = null
    mutableTransport.value = null
    mutableState.value = ConnectionState.Connecting
    TechnicalLogStore.info("TRUST", "Starting tunnel")
    ensureNetworkManager(context.applicationContext)
    try {
      VpnService.start(context, config)
    } catch (failure: Throwable) {
      connectionRequested = false
      activeLifecycleAttempt = null
      mutableStopped.value = true
      mutableState.value = ConnectionState.Failed
      throw failure
    }
    scheduleConnectionTimeout(context.applicationContext, generation)
  }

  private fun scheduleConnectionTimeout(context: Context, generation: Int) {
    scope.launch {
      delay(CONNECTION_TIMEOUT_MS)
      handleConnectionTimeout(context, generation)
    }
  }

  @Synchronized
  private fun handleConnectionTimeout(context: Context, generation: Int) {
    if (
      generation != probeGeneration ||
      !connectionRequested ||
      mutableState.value != ConnectionState.Connecting
    ) return
    mutableFailureMessage.value = context.getString(R.string.trust_connection_timeout)
    connectionRequested = false
    VpnService.stop(context)
    mutableState.value = ConnectionState.Failed
    TechnicalLogStore.error("TRUST", "Connection timed out")
  }

  @Synchronized
  fun stop(context: Context) {
    mutableFailureMessage.value = null
    AndroidTunnelLifecycleOwner.stop(context)
  }

  @Synchronized
  internal fun stopEngine(context: Context, attempt: LifecycleAttempt? = null) {
    if (attempt != null && activeLifecycleAttempt != attempt) return
    connectionRequested = false
    hasConnected = false
    probeGeneration += 1
    mutableTransport.value = null
    if (mutableState.value == ConnectionState.Disconnected) {
      activeLifecycleAttempt = null
      mutableStopped.value = true
      return
    }
    VpnService.stop(context)
    TechnicalLogStore.info("TRUST", "Tunnel stopped by user")
  }

  @Synchronized
  fun refreshLatencies(profiles: List<TrustTunnelCatalogEntry>) {
    if (latencyJob?.isActive == true) return
    mutableLatencyChecking.value = true
    latencyJob = scope.launch {
      try {
        mutableLatencies.value = profiles.mapNotNull { profile ->
          measureEndpointLatency(profile.config)?.let { profile.id to it }
        }.toMap()
      } finally {
        mutableLatencyChecking.value = false
        synchronized(this@TrustTunnelManager) { latencyJob = null }
      }
    }
  }

  @Synchronized
  override fun onStateChanged(state: Int) {
    val nativeState = VpnState.getByCode(state)
    mutableState.value = when (nativeState) {
      VpnState.DISCONNECTED -> {
        hasConnected = false
        activeLifecycleAttempt = null
        mutableStopped.value = true
        if (connectionRequested) {
          connectionRequested = false
          mutableFailureMessage.value =
            appContext.getString(R.string.trust_connection_failed)
          TechnicalLogStore.error("TRUST", "Core ended the connection with an error")
          ConnectionState.Failed
        } else if (mutableFailureMessage.value != null) {
          ConnectionState.Failed
        } else {
          ConnectionState.Disconnected
        }
      }
      VpnState.CONNECTED -> {
        if (!connectionRequested || activeLifecycleAttempt == null) {
          TechnicalLogStore.warning("TRUST", "Ignored a stale CONNECTED event")
          ConnectionState.Disconnected
        } else {
          hasConnected = true
          TechnicalLogStore.info("TRUST", "Tunnel connected")
          ConnectionState.Connected
        }
      }
      VpnState.CONNECTING ->
        if (connectionRequested && activeLifecycleAttempt != null) {
          ConnectionState.Connecting
        } else {
          mutableState.value
        }
      VpnState.WAITING_RECOVERY,
      VpnState.RECOVERING,
      VpnState.WAITING_FOR_NETWORK,
      -> {
        TechnicalLogStore.warning("TRUST", "Physical network transition: ${nativeState.name}")
        trustRecoveryUiState(hasConnected, connectionRequested)
      }
    }
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

  @Synchronized
  private fun ensureNetworkManager(context: Context) {
    if (networkManagerStarted) return
    VpnService.startNetworkManager(context)
    networkManagerStarted = true
    TechnicalLogStore.info("TRUST", "Physical network monitor started on demand")
  }

  private fun measureEndpointLatency(config: String): Int? {
    val addresses = Regex("""(?m)^\s*addresses\s*=\s*\[(.*)]\s*$""")
      .find(config)
      ?.groupValues
      ?.get(1)
      ?.let { values ->
        Regex(""""([^"]+)"""").findAll(values).map { it.groupValues[1] }.toList()
      }
      .orEmpty()
    return addresses.mapNotNull(::measureAddress).minOrNull()
  }

  private fun measureAddress(address: String): Int? {
    val endpoint = parseAddress(address) ?: return null
    var connected = false
    val elapsed = measureTimeMillis {
      connected = runCatching {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(endpoint.first, endpoint.second), 2_000)
        }
        true
      }.getOrDefault(false)
    }
    return elapsed.toInt().takeIf { connected }
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
