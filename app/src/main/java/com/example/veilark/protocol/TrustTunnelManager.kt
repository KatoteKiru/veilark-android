package com.example.veilark.protocol

import android.content.Context
import com.adguard.trusttunnel.AppNotifier
import com.adguard.trusttunnel.VpnService
import com.adguard.trusttunnel.VpnState
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.NativeRuntimeState
import com.example.veilark.vpn.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.system.measureTimeMillis

enum class TrustTunnelHealth {
  Idle,
  Checking,
  Healthy,
  Limited,
}

object TrustTunnelManager : AppNotifier {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutableState = MutableStateFlow(ConnectionState.Disconnected)
  val state = mutableState.asStateFlow()
  private val mutableFailureMessage = MutableStateFlow<String?>(null)
  val failureMessage = mutableFailureMessage.asStateFlow()
  private val mutableHealth = MutableStateFlow(TrustTunnelHealth.Idle)
  val health = mutableHealth.asStateFlow()
  private val mutableTransport = MutableStateFlow<String?>(null)
  val transport = mutableTransport.asStateFlow()
  private val mutableLatencies = MutableStateFlow<Map<String, Int>>(emptyMap())
  val latencies = mutableLatencies.asStateFlow()
  private val mutableLatencyChecking = MutableStateFlow(false)
  val latencyChecking = mutableLatencyChecking.asStateFlow()
  @Volatile
  private var connectionRequested = false
  @Volatile
  private var hasConnected = false
  @Volatile
  private var probeGeneration = 0

  fun initialize(context: Context) {
    VpnService.startNetworkManager(context.applicationContext)
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

  fun start(context: Context, config: String) {
    NativeRuntimeState.requireTrustTunnel()
    check(VpnServiceConfigValidator.isValid(config)) {
      "Некорректная конфигурация TrustTunnel"
    }
    connectionRequested = true
    hasConnected = false
    val generation = ++probeGeneration
    mutableFailureMessage.value = null
    mutableHealth.value = TrustTunnelHealth.Checking
    mutableTransport.value = null
    mutableState.value = ConnectionState.Connecting
    TechnicalLogStore.info("TRUST", "Запуск туннеля")
    VpnService.start(context, config)
    scheduleConnectionTimeout(context.applicationContext, generation)
  }

  private fun scheduleConnectionTimeout(context: Context, generation: Int) {
    scope.launch {
      delay(CONNECTION_TIMEOUT_MS)
      if (
        generation == probeGeneration &&
        connectionRequested &&
        mutableState.value == ConnectionState.Connecting
      ) {
        mutableFailureMessage.value =
          "TrustTunnel не подключился за 45 секунд. Проверьте профиль и сеть"
        connectionRequested = false
        VpnService.stop(context)
        mutableState.value = ConnectionState.Failed
        TechnicalLogStore.error("TRUST", "Таймаут подключения")
      }
    }
  }

  fun stop(context: Context) {
    connectionRequested = false
    hasConnected = false
    probeGeneration += 1
    mutableFailureMessage.value = null
    mutableHealth.value = TrustTunnelHealth.Idle
    mutableTransport.value = null
    VpnService.stop(context)
    mutableState.value = ConnectionState.Disconnected
    TechnicalLogStore.info("TRUST", "Туннель остановлен пользователем")
  }

  fun refreshLatencies(profiles: List<TrustTunnelCatalogEntry>) {
    scope.launch {
      mutableLatencyChecking.value = true
      try {
        mutableLatencies.value = profiles.mapNotNull { profile ->
          measureEndpointLatency(profile.config)?.let { profile.id to it }
        }.toMap()
      } finally {
        mutableLatencyChecking.value = false
      }
    }
  }

  override fun onStateChanged(state: Int) {
    val nativeState = VpnState.getByCode(state)
    mutableState.value = when (nativeState) {
      VpnState.DISCONNECTED -> {
        hasConnected = false
        if (connectionRequested) {
          connectionRequested = false
          mutableFailureMessage.value =
            "TrustTunnel не установил соединение. Проверьте доступность сервера и профиль"
          TechnicalLogStore.error("TRUST", "Ядро завершило подключение с ошибкой")
          ConnectionState.Failed
        } else if (mutableFailureMessage.value != null) {
          ConnectionState.Failed
        } else {
          ConnectionState.Disconnected
        }
      }
      VpnState.CONNECTED -> {
        hasConnected = true
        TechnicalLogStore.info("TRUST", "Туннель подключён")
        verifyTunnel(++probeGeneration)
        ConnectionState.Connected
      }
      VpnState.CONNECTING -> ConnectionState.Connecting
      VpnState.WAITING_RECOVERY,
      VpnState.RECOVERING,
      VpnState.WAITING_FOR_NETWORK,
      -> {
        TechnicalLogStore.warning("TRUST", "Смена физической сети: ${nativeState.name}")
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
      TechnicalLogStore.info("TRUST", "Согласован транспорт ${transport ?: "unknown"}")
    }
  }

  private fun verifyTunnel(generation: Int) {
    mutableHealth.value = TrustTunnelHealth.Checking
    scope.launch {
      var successfulProbes = 0
      for (attempt in 0..2) {
        if (generation != probeGeneration) return@launch
        successfulProbes = HEALTH_ENDPOINTS.count { endpoint ->
          probe(endpoint.first, endpoint.second).success
        }
        if (successfulProbes >= 2) break
        if (attempt < 2) delay(1_500)
      }
      if (generation != probeGeneration) return@launch
      if (successfulProbes >= 2) {
        mutableHealth.value = TrustTunnelHealth.Healthy
        mutableFailureMessage.value = null
        TechnicalLogStore.info("TRUST", "Контрольная проверка сети пройдена")
      } else {
        mutableHealth.value = TrustTunnelHealth.Limited
        // A third-party HTTP endpoint is not authoritative for VPN state. Showing this as
        // a connection failure caused a working tunnel to look as if it was reconnecting.
        mutableFailureMessage.value = null
        TechnicalLogStore.warning("TRUST", "Контрольная проверка сети ограничена")
      }
    }
  }

  private fun probe(name: String, address: String): TrustProbeResult {
    val started = System.nanoTime()
    var connection: HttpURLConnection? = null
    return try {
      val active = URL(address).openConnection() as HttpURLConnection
      connection = active
      active.connectTimeout = 8_000
      active.readTimeout = 8_000
      active.instanceFollowRedirects = false
      active.useCaches = false
      active.setRequestProperty("User-Agent", "Veilark-Health/1")
      val code = active.responseCode
      val elapsed = (System.nanoTime() - started) / 1_000_000
      TechnicalLogStore.info("HEALTH", "Trust $name HTTPS=$code latency=${elapsed}ms")
      TrustProbeResult(code in 200..399)
    } catch (failure: Exception) {
      val elapsed = (System.nanoTime() - started) / 1_000_000
      TechnicalLogStore.warning(
        "HEALTH",
        "Trust $name failed=${failure.javaClass.simpleName} latency=${elapsed}ms",
      )
      TrustProbeResult(false)
    } finally {
      connection?.disconnect()
    }
  }

  private data class TrustProbeResult(val success: Boolean)

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
  if (wasConnected && connectionRequested) ConnectionState.Connected
  else ConnectionState.Connecting

private object VpnServiceConfigValidator {
  fun isValid(config: String): Boolean =
    com.adguard.trusttunnel.VpnServiceConfig.parseToml(config) != null
}

private val HEALTH_ENDPOINTS = listOf(
  "Google" to "https://www.gstatic.com/generate_204",
  "Cloudflare" to "https://1.1.1.1/cdn-cgi/trace",
  "YouTube" to "https://www.youtube.com/generate_204",
)

private const val CONNECTION_TIMEOUT_MS = 45_000L
