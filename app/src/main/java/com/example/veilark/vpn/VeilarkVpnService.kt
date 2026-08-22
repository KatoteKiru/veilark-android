package com.example.veilark.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.veilark.BuildConfig
import com.example.veilark.MainActivity
import com.example.veilark.NativeRuntimeState
import com.example.veilark.R
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.lifecycle.AndroidTunnelLifecycleOwner
import com.example.veilark.lifecycle.LifecycleAttempt
import com.example.veilark.lifecycle.startupSideEffectAllowed
import com.example.veilark.profile.SecureProfileStore
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.KeyStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

enum class ConnectionState {
  Disconnected,
  Connecting,
  Connected,
  Failed,
}

enum class StartupStage(val safeTitle: String) {
  Idle("Ожидание"),
  Config("Проверка профиля"),
  Network("Поиск физической сети"),
  Core("Запуск сетевого ядра"),
  Tun("Создание VPN-туннеля"),
  Internet("Проверка доступа"),
}

class VeilarkVpnService :
  VpnService(),
  PlatformInterface,
  CommandServerHandler {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val startupLock = Any()
  private val connectivity by lazy {
    getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  }
  private var commandServer: CommandServer? = null
  private var tunDescriptor: ParcelFileDescriptor? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var interfaceListener: InterfaceUpdateListener? = null
  private var underlyingNetwork: Network? = null
  private var activeProfileName: String? = null
  private var connectionStartedAtMillis = 0L
  private val dnsTransport = AndroidDnsTransport { underlyingNetwork ?: findPhysicalNetwork() }
  @Volatile
  private var startupAttempt = 0
  private var lifecycleAttempt: LifecycleAttempt? = null
  private var startupJob: Job? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      shutdown()
      return START_NOT_STICKY
    }

    lifecycleAttempt = intent?.lifecycleAttempt()
    if (!ownsLifecycleAttempt()) {
      TechnicalLogStore.warning("LIFECYCLE", "Устаревший запуск sing-box отклонён")
      stopSelf(startId)
      return START_NOT_STICKY
    }

    val configPath = intent?.getStringExtra(EXTRA_CONFIG_PATH)
    if (configPath.isNullOrBlank()) {
      publishState(ConnectionState.Failed)
      stopSelf()
      return START_NOT_STICKY
    }

    activeProfileName = readActiveProfileName()
    connectionStartedAtMillis = 0L
    val startingNotification = createStatusNotification("Подключение…")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        startingNotification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, startingNotification)
    }
    val attempt = synchronized(startupLock) { ++startupAttempt }
    TechnicalLogStore.info("SING-BOX", "Запуск сетевого ядра")
    publishState(ConnectionState.Connecting)
    mutableFailureMessage.value = null
    mutableFailureCode.value = null
    mutableDiagnosticReport.value = null
    val lifecycleToken = lifecycleAttempt
    scope.launch {
      kotlinx.coroutines.delay(CONNECTION_TIMEOUT_MS)
      if (
        attempt == startupAttempt && ownsLifecycleAttempt() &&
        mutableState.value == ConnectionState.Connecting
      ) {
        runCatching {
          currentStartupSideEffect(attempt, lifecycleToken) {
            check(mutableState.value == ConnectionState.Connecting)
            mutableFailureMessage.value =
              "Сетевое ядро не подключилось за 45 секунд. Проверьте профиль и сеть"
            mutableFailureCode.value = "VPN-CORE-TIMEOUT"
            TechnicalLogStore.error("SING-BOX", "VPN-CORE-TIMEOUT: таймаут подключения")
          }
          requireCurrentStartup(attempt, lifecycleToken)
          shutdown(delayStop = true)
        }
      }
    }
    val job = scope.launch(start = CoroutineStart.LAZY) {
      var localServer: CommandServer? = null
      var ownedNetworkCallback: ConnectivityManager.NetworkCallback? = null
      var connected = false
      try {
        requireCurrentStartup(attempt, lifecycleToken)
        currentStartupSideEffect(attempt, lifecycleToken) {
          mutableStartupStage.value = StartupStage.Config
        }
        val config = SecureProfileStore.load(this@VeilarkVpnService, configPath)
        Libbox.checkConfig(config)
        requireCurrentStartup(attempt, lifecycleToken)
        TechnicalLogStore.info("CONFIG", describeConfig(config))

        requireCurrentStartup(attempt, lifecycleToken)
        currentStartupSideEffect(attempt, lifecycleToken) {
          mutableStartupStage.value = StartupStage.Network
        }
        val physicalNetwork = findPhysicalNetwork()
          ?: error("Physical network is unavailable")
        requireCurrentStartup(attempt, lifecycleToken)
        currentStartupSideEffect(attempt, lifecycleToken) {
          underlyingNetwork = physicalNetwork
        }
        ownedNetworkCallback = startNetworkMonitor(attempt, lifecycleToken)

        currentStartupSideEffect(attempt, lifecycleToken) {
          mutableStartupStage.value = StartupStage.Core
        }
        val server = CommandServer(this@VeilarkVpnService, this@VeilarkVpnService)
        localServer = server
        requireCurrentStartup(attempt, lifecycleToken)
        server.start()
        requireCurrentStartup(attempt, lifecycleToken)
        currentStartupSideEffect(attempt, lifecycleToken) { commandServer = server }
        requireCurrentStartup(attempt, lifecycleToken)
        server.startOrReloadService(config, OverrideOptions())
        requireCurrentStartup(attempt, lifecycleToken)

        requireCurrentStartup(attempt, lifecycleToken)
        val coreVersion = runCatching { Libbox.version() }.getOrDefault("unknown")
        requireCurrentStartup(attempt, lifecycleToken)
        currentStartupSideEffect(attempt, lifecycleToken) {
          mutableStartupStage.value = StartupStage.Idle
          mutableState.value = ConnectionState.Connected
          mutableFailureMessage.value = null
          connectionStartedAtMillis = System.currentTimeMillis()
          updateNotification("Защищено")
          TechnicalLogStore.info("SING-BOX", "Туннель подключён; core=$coreVersion")
          LatencyMonitor.start()
          connected = true
        }
      } catch (_: CancellationException) {
        // A newer lifecycle command owns all user-visible state.
      } catch (failure: Throwable) {
        if (isCurrentStartup(attempt, lifecycleToken)) {
          val stage = mutableStartupStage.value
          currentStartupSideEffect(attempt, lifecycleToken) {
            mutableState.value = ConnectionState.Failed
            mutableFailureMessage.value =
              "${stage.safeTitle}. ${classifyFailure(failure, stage)}"
            mutableFailureCode.value = diagnosticCode(failure, stage)
            TechnicalLogStore.error(
              "SING-BOX",
              "${mutableFailureCode.value}: ${classifyFailure(failure, stage)}",
            )
            mutableDiagnosticReport.value = buildDiagnosticReport(failure, stage)
            updateNotification("Ошибка конфигурации или соединения")
          }
          requireCurrentStartup(attempt, lifecycleToken)
          shutdown(delayStop = true)
        }
      } finally {
        if (!connected || !isCurrentStartup(attempt, lifecycleToken)) {
          cleanupStartupResources(localServer, ownedNetworkCallback)
        }
      }
    }
    synchronized(startupLock) {
      if (isCurrentStartup(attempt, lifecycleToken)) {
        startupJob = job
      } else {
        job.cancel()
      }
    }
    job.start()
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

  override fun onRevoke() {
    shutdown()
    super.onRevoke()
  }

  override fun onDestroy() {
    val (ownsAttempt, stoppingAttempt, settlingJob) = synchronized(startupLock) {
      startupAttempt += 1
      Triple(ownsLifecycleAttempt(), lifecycleAttempt, startupJob)
    }
    settlingJob?.cancel()
    synchronized(startupLock) { closeResources() }
    if (ownsAttempt) {
      if (mutableState.value == ConnectionState.Connecting ||
        mutableState.value == ConnectionState.Connected
      ) {
        mutableState.value = ConnectionState.Disconnected
      }
      if (settlingJob?.isCompleted == false) {
        settlingJob.invokeOnCompletion { finishTeardown(stoppingAttempt) }
      } else {
        finishTeardown(stoppingAttempt)
      }
    }
    scope.cancel()
    super.onDestroy()
  }

  override fun autoDetectInterfaceControl(fd: Int) {
    protect(fd)
  }

  override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

  override fun useProcFS(): Boolean = false

  override fun underNetworkExtension(): Boolean = false

  override fun includeAllNetworks(): Boolean = false

  override fun openTun(options: TunOptions): Int {
    mutableStartupStage.value = StartupStage.Tun
    check(prepare(this) == null) { "VPN permission was revoked" }

    val builder = Builder()
      .setSession("Veilark")
      .setMtu(options.mtu)
      .setBlocking(false)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      builder.setMetered(false)
      underlyingNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
    }

    addAddresses(builder, options)
    if (options.autoRoute) {
      builder.addDnsServer(options.dnsServerAddress.value)
      addRoutes(builder, options)
    }
    addApplicationRules(builder, options)

    val descriptor = builder.establish() ?: error("Android rejected TUN interface")
    tunDescriptor?.close()
    tunDescriptor = descriptor
    return descriptor.fd
  }

  private fun addAddresses(builder: Builder, options: TunOptions) {
    options.inet4Address.forEachRemaining { builder.addAddress(it.address(), it.prefix()) }
    options.inet6Address.forEachRemaining { builder.addAddress(it.address(), it.prefix()) }
  }

  private fun addRoutes(builder: Builder, options: TunOptions) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      var hasInet4Route = false
      options.inet4RouteAddress.forEachRemaining {
        hasInet4Route = true
        builder.addRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
      }
      if (!hasInet4Route) builder.addRoute(IpPrefix(InetAddress.getByName("0.0.0.0"), 0))
      var hasInet6Route = false
      options.inet6RouteAddress.forEachRemaining {
        hasInet6Route = true
        builder.addRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
      }
      if (!hasInet6Route) builder.addRoute(IpPrefix(InetAddress.getByName("::"), 0))
      options.inet4RouteExcludeAddress.forEachRemaining {
        builder.excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
      }
      options.inet6RouteExcludeAddress.forEachRemaining {
        builder.excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
      }
    } else {
      options.inet4RouteRange.forEachRemaining { builder.addRoute(it.address(), it.prefix()) }
      options.inet6RouteRange.forEachRemaining { builder.addRoute(it.address(), it.prefix()) }
    }
  }

  private fun addApplicationRules(builder: Builder, options: TunOptions) {
    options.includePackage.forEachRemaining {
      try {
        builder.addAllowedApplication(it)
      } catch (_: NameNotFoundException) {
        // Stale package names are safely ignored.
      }
    }
    options.excludePackage.forEachRemaining {
      try {
        builder.addDisallowedApplication(it)
      } catch (_: NameNotFoundException) {
        // Stale package names are safely ignored.
      }
    }
  }

  override fun findConnectionOwner(
    ipProtocol: Int,
    sourceAddress: String,
    sourcePort: Int,
    destinationAddress: String,
    destinationPort: Int,
  ): ConnectionOwner {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      error("Per-app routing requires Android 10")
    }
    val uid = connectivity.getConnectionOwnerUid(
      ipProtocol,
      InetSocketAddress(sourceAddress, sourcePort),
      InetSocketAddress(destinationAddress, destinationPort),
    )
    check(uid != Process.INVALID_UID) { "Connection owner not found" }
    return ConnectionOwner().apply {
      userId = uid
      userName = packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
      setAndroidPackageNames(StringArray(packageManager.getPackagesForUid(uid)?.asList().orEmpty()))
    }
  }

  override fun getInterfaces(): NetworkInterfaceIterator {
    val interfaces = connectivity.allNetworks.mapNotNull { network ->
      val link = connectivity.getLinkProperties(network) ?: return@mapNotNull null
      val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
      val javaInterface = NetworkInterface.getByName(link.interfaceName) ?: return@mapNotNull null
      BoxNetworkInterface().apply {
        name = javaInterface.name
        index = javaInterface.index
        mtu = runCatching { javaInterface.mtu }.getOrDefault(0)
        addresses = StringArray(javaInterface.interfaceAddresses.map {
          val host = if (it.address is Inet6Address) {
            Inet6Address.getByAddress(it.address.address).hostAddress
          } else {
            it.address.hostAddress
          }
          "$host/${it.networkPrefixLength}"
        })
        dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress })
        type = when {
          capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
          capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
          capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
          else -> Libbox.InterfaceTypeOther
        }
        metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
      }
    }
    return NetworkInterfaceArray(interfaces)
  }

  override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
    interfaceListener = listener
    startNetworkMonitor(startupAttempt, lifecycleAttempt)
    publishDefaultInterface(connectivity.activeNetwork)
  }

  override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
    interfaceListener = null
  }

  private fun startNetworkMonitor(
    attempt: Int,
    lifecycleToken: LifecycleAttempt?,
  ): ConnectivityManager.NetworkCallback? {
    requireCurrentStartup(attempt, lifecycleToken)
    if (networkCallback != null) return null
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) = considerUnderlyingNetwork(network)
      override fun onLost(network: Network) {
        if (network == underlyingNetwork) {
          TechnicalLogStore.warning("NETWORK", "Физическая сеть потеряна, ищем замену")
          underlyingNetwork = findPhysicalNetwork(excluding = network)
          publishDefaultInterface(underlyingNetwork)
          updateUnderlyingNetworks(underlyingNetwork)
          updateNotification(if (underlyingNetwork == null) "Ожидание сети…" else "Смена сети…")
          commandServer?.resetNetwork()
          if (underlyingNetwork != null) updateNotification("Защищено")
        }
      }
      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
          considerUnderlyingNetwork(network)
        }
      }
    }
    connectivity.registerNetworkCallback(
      NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build(),
      callback,
    )
    try {
      currentStartupSideEffect(attempt, lifecycleToken) {
        if (networkCallback == null) {
          networkCallback = callback
        } else {
          connectivity.unregisterNetworkCallback(callback)
          return null
        }
      }
    } catch (failure: Throwable) {
      runCatching { connectivity.unregisterNetworkCallback(callback) }
      throw failure
    }
    return callback
  }

  private fun considerUnderlyingNetwork(network: Network) {
    val caps = connectivity.getNetworkCapabilities(network) ?: return
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
      !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    ) {
      return
    }
    if (underlyingNetwork == network) {
      if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
        publishDefaultInterface(network)
      } else {
        val replacement = findPhysicalNetwork(excluding = network)
        if (replacement != null) {
          adoptUnderlyingNetwork(replacement)
        }
      }
      return
    }
    val currentCaps = underlyingNetwork?.let(connectivity::getNetworkCapabilities)
    if (!NetworkHandoverPolicy.shouldAdopt(
        currentUsable = isStillUsable(underlyingNetwork),
        currentPriority = currentCaps?.let(::networkPriority)
          ?: NetworkHandoverPolicy.PRIORITY_OTHER,
        candidateValidated =
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        candidatePriority = networkPriority(caps),
        candidateIsActive = connectivity.activeNetwork == network,
      )
    ) return
    adoptUnderlyingNetwork(network)
  }

  private fun adoptUnderlyingNetwork(network: Network) {
    val caps = connectivity.getNetworkCapabilities(network) ?: return
    underlyingNetwork = network
    publishDefaultInterface(network)
    updateUnderlyingNetworks(network)
    TechnicalLogStore.info("NETWORK", "Туннель переведён на ${networkType(caps)}")
    updateNotification("Защищено")
    commandServer?.resetNetwork()
  }

  private fun updateUnderlyingNetworks(network: Network?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
      runCatching {
        setUnderlyingNetworks(network?.let { arrayOf(it) })
      }.onFailure {
        TechnicalLogStore.warning("NETWORK", "Android не принял смену базовой сети")
      }
    }
  }

  private fun isStillUsable(network: Network?): Boolean {
    network ?: return false
    val caps = connectivity.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
      !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
  }

  private fun networkType(caps: NetworkCapabilities): String = when {
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильную сеть"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
    else -> "новую физическую сеть"
  }

  private fun networkPriority(caps: NetworkCapabilities): Int = when {
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
      NetworkHandoverPolicy.PRIORITY_ETHERNET
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
      NetworkHandoverPolicy.PRIORITY_WIFI
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
      NetworkHandoverPolicy.PRIORITY_CELLULAR
    else -> NetworkHandoverPolicy.PRIORITY_OTHER
  }

  private fun findPhysicalNetwork(excluding: Network? = null): Network? {
    fun isUsable(network: Network): Boolean {
      val caps = connectivity.getNetworkCapabilities(network) ?: return false
      return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
    return NetworkHandoverPolicy.selectReplacement(
      active = connectivity.activeNetwork,
      candidates = connectivity.allNetworks.toList(),
      excluding = excluding,
      isUsable = ::isUsable,
      priority = { network ->
        connectivity.getNetworkCapabilities(network)?.let(::networkPriority)
          ?: NetworkHandoverPolicy.PRIORITY_OTHER
      },
    )
  }

  private fun publishDefaultInterface(network: Network?) {
    val listener = interfaceListener ?: return
    if (network == null) {
      listener.updateDefaultInterface("", -1, false, false)
      return
    }
    val link = connectivity.getLinkProperties(network) ?: return
    val name = link.interfaceName ?: return
    val index = NetworkInterface.getByName(name)?.index ?: return
    val caps = connectivity.getNetworkCapabilities(network)
    val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
    listener.updateDefaultInterface(name, index, metered, false)
  }

  private fun describeConfig(config: String): String = runCatching {
    val root = JSONObject(config)
    val dns = root.optJSONObject("dns")
    val route = root.optJSONObject("route")
    val tun = root.optJSONArray("inbounds")?.optJSONObject(0)
    buildString {
      append("dns=")
      append(dns?.optString("strategy").orEmpty().ifBlank { "default" })
      append("; mtu=")
      append(tun?.optInt("mtu", 0))
      append("; stack=")
      append(tun?.optString("stack").orEmpty().ifBlank { "default" })
      append("; routeRules=")
      append(route?.optJSONArray("rules")?.length() ?: 0)
      append("; includedApps=")
      append(tun?.optJSONArray("include_package")?.length() ?: 0)
      append("; excludedApps=")
      append(tun?.optJSONArray("exclude_package")?.length() ?: 0)
      append("; final=")
      append(route?.optString("final").orEmpty().ifBlank { "unset" })
    }
  }.getOrElse { "Не удалось разобрать сводку конфигурации" }

  private fun classifyFailure(failure: Throwable, stage: StartupStage): String {
    val chain = generateSequence(failure) { it.cause }
      .joinToString(" ") { it.message.orEmpty() }
      .lowercase()
    return when {
      "tunnel verification failed" in chain ->
        "Туннель создан, но контрольный трафик через него не прошёл"
      "certificate" in chain || "x509" in chain ->
        "Сертификат сервера не прошёл проверку"
      "dns" in chain || "lookup" in chain ->
        "Не удалось разрешить адрес сервера или DNS"
      "timeout" in chain || "deadline" in chain ->
        "Сеть не ответила за отведённое время"
      "connection refused" in chain ->
        "Сервер отклонил соединение"
      "network is unreachable" in chain || "no route" in chain ->
        "Физическая сеть недоступна"
      else ->
        "Сетевое ядро не смогло запустить профиль (${failure.javaClass.simpleName})"
    }
  }

  private fun diagnosticCode(failure: Throwable, stage: StartupStage): String {
    val type = generateSequence(failure) { it.cause }.last().javaClass.simpleName
      .replace(Regex("[^A-Za-z0-9]"), "")
      .uppercase()
      .take(24)
    return "VPN-${stage.name.uppercase()}-${type.ifBlank { "UNKNOWN" }}"
  }

  private fun buildDiagnosticReport(failure: Throwable, stage: StartupStage): String {
    val code = diagnosticCode(failure, stage)
    val exceptions = generateSequence(failure) { it.cause }
      .take(8)
      .joinToString("\n") {
        "${it.javaClass.simpleName}: ${sanitizeDiagnostic(it.message.orEmpty())}"
      }
    val stderr = File(cacheDir, "libbox-stderr.log")
      .takeIf(File::isFile)
      ?.readLines()
      ?.takeLast(80)
      ?.joinToString("\n", transform = ::sanitizeDiagnostic)
      .orEmpty()
    return buildString {
      appendLine("Veilark ${BuildConfig.VERSION_NAME}")
      appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
      appendLine("Device ${Build.MANUFACTURER} ${Build.MODEL}")
      appendLine("Stage ${stage.name}")
      appendLine("Code $code")
      appendLine("Exception")
      appendLine(exceptions)
      if (stderr.isNotBlank()) {
        appendLine("Core log")
        appendLine(stderr)
      }
    }.trim()
  }

  private fun sanitizeDiagnostic(value: String): String = value
    .replace(Regex("""(?i)([a-z][a-z0-9+.-]*://)[^@\s/]+@"""), "$1***@")
    .replace(
      Regex("""(?i)(password|passwd|token|uuid|auth|private[_-]?key)=([^&\s]+)"""),
      "$1=***",
    )
    .replace(
      Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""),
      "***",
    )

  override fun clearDNSCache() = Unit

  override fun readWIFIState() = null

  override fun localDNSTransport(): LocalDNSTransport = dnsTransport

  @OptIn(ExperimentalEncodingApi::class)
  override fun systemCertificates(): StringIterator {
    val store = KeyStore.getInstance("AndroidCAStore")
    store.load(null)
    val certificates = buildList {
      val aliases = store.aliases()
      while (aliases.hasMoreElements()) {
        val certificate = store.getCertificate(aliases.nextElement()) ?: continue
        add(
          "-----BEGIN CERTIFICATE-----\n" +
            Base64.Mime.encode(certificate.encoded) +
            "\n-----END CERTIFICATE-----",
        )
      }
    }
    return StringArray(certificates)
  }

  override fun sendNotification(notification: Notification) = Unit

  override fun serviceStop() = shutdown()

  override fun serviceReload() = Unit

  override fun getSystemProxyStatus(): SystemProxyStatus =
    SystemProxyStatus().apply {
      available = false
      enabled = false
    }

  override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

  override fun writeDebugMessage(message: String?) {
    message?.takeIf(String::isNotBlank)?.let {
      TechnicalLogStore.info("SING-BOX", it)
    }
  }

  private fun shutdown(delayStop: Boolean = false) {
    val (ownsAttempt, stoppingAttempt, settlingJob) = synchronized(startupLock) {
      startupAttempt += 1
      Triple(ownsLifecycleAttempt(), lifecycleAttempt, startupJob)
    }
    settlingJob?.cancel()
    synchronized(startupLock) { closeResources() }
    if (ownsAttempt) {
      mutableState.value = if (delayStop) ConnectionState.Failed else ConnectionState.Disconnected
      if (settlingJob?.isCompleted == false) {
        settlingJob.invokeOnCompletion { finishTeardown(stoppingAttempt) }
      } else {
        finishTeardown(stoppingAttempt)
      }
    }
    if (!delayStop) TechnicalLogStore.info("SING-BOX", "Туннель остановлен")
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun publishState(state: ConnectionState) {
    if (ownsLifecycleAttempt()) mutableState.value = state
  }

  private fun ownsLifecycleAttempt(): Boolean =
    lifecycleAttempt != null && lifecycleAttempt == activeLifecycleAttempt

  private fun isCurrentStartup(attempt: Int, lifecycleToken: LifecycleAttempt?): Boolean =
    startupSideEffectAllowed(
      expectedStartupAttempt = attempt,
      currentStartupAttempt = startupAttempt,
      expectedLifecycleAttempt = lifecycleToken,
      activeLifecycleAttempt = activeLifecycleAttempt,
    )

  private fun requireCurrentStartup(attempt: Int, lifecycleToken: LifecycleAttempt?) {
    if (!isCurrentStartup(attempt, lifecycleToken)) {
      throw CancellationException("Stale sing-box startup attempt")
    }
  }

  private inline fun <T> currentStartupSideEffect(
    attempt: Int,
    lifecycleToken: LifecycleAttempt?,
    block: () -> T,
  ): T = synchronized(startupLock) {
    requireCurrentStartup(attempt, lifecycleToken)
    block()
  }

  private fun cleanupStartupResources(
    server: CommandServer?,
    callback: ConnectivityManager.NetworkCallback?,
  ) {
    synchronized(startupLock) {
      server?.let {
        runCatching { it.closeService() }
        runCatching { it.close() }
        if (commandServer === it) commandServer = null
      }
      callback?.let {
        if (networkCallback === it) {
          runCatching { connectivity.unregisterNetworkCallback(it) }
          networkCallback = null
        }
      }
    }
  }

  @Synchronized
  private fun finishTeardown(attempt: LifecycleAttempt?) {
    if (activeLifecycleAttempt == attempt) {
      mutableStopped.value = true
      clearActiveAttempt(attempt)
    }
  }

  private fun Intent.lifecycleAttempt(): LifecycleAttempt? {
    val attemptId = getLongExtra(EXTRA_ATTEMPT_ID, 0L)
    val epoch = getLongExtra(EXTRA_ENGINE_EPOCH, 0L)
    return if (attemptId > 0L && epoch > 0L) {
      LifecycleAttempt(attemptId, epoch, com.example.veilark.lifecycle.EngineId.SingBox)
    } else {
      null
    }
  }

  private fun closeResources() {
    LatencyMonitor.stop()
    runCatching { commandServer?.closeService() }
    runCatching { commandServer?.close() }
    commandServer = null
    runCatching { tunDescriptor?.close() }
    tunDescriptor = null
    networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
    networkCallback = null
    underlyingNetwork = null
    connectionStartedAtMillis = 0L
  }

  private fun createStatusNotification(status: String): android.app.Notification {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          "VPN-соединение",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Состояние активного VPN-туннеля Veilark"
          setShowBadge(false)
          lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        },
      )
    }
    val open = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val stop = PendingIntent.getService(
      this,
      1,
      Intent(this, VeilarkVpnService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_vpn_status)
      .setContentTitle("Veilark · sing-box")
      .setContentText(status)
      .setSubText(activeProfileName?.take(64))
      .setContentIntent(open)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(
        NotificationCompat.Builder(this, CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_vpn_status)
          .setContentTitle("Veilark · VPN")
          .setContentText(if (status.startsWith("Защищено")) "Соединение активно" else status)
          .setCategory(NotificationCompat.CATEGORY_SERVICE)
          .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
          .build(),
      )
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(0, "Отключить", stop)
    if (connectionStartedAtMillis > 0L && status.startsWith("Защищено")) {
      notification
        .setWhen(connectionStartedAtMillis)
        .setUsesChronometer(true)
        .setShowWhen(true)
    } else {
      notification.setShowWhen(false)
    }
    return notification.build()
  }

  private fun updateNotification(status: String) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, createStatusNotification(status))
  }

  private fun readActiveProfileName(): String? =
    getSharedPreferences("profile_meta", MODE_PRIVATE)
      .getString("sing_display_name", null)
      ?.trim()
      ?.takeIf(String::isNotEmpty)

  companion object {
    private val ACTION_START = "${BuildConfig.APPLICATION_ID}.START"
    private val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.STOP"
    private const val EXTRA_CONFIG_PATH = "config_path"
    private const val EXTRA_ATTEMPT_ID = "lifecycle_attempt_id"
    private const val EXTRA_ENGINE_EPOCH = "lifecycle_engine_epoch"
    private const val CHANNEL_ID = "veilark_vpn"
    private const val NOTIFICATION_ID = 1001
    private const val CONNECTION_TIMEOUT_MS = 45_000L

    private val mutableState = MutableStateFlow(ConnectionState.Disconnected)
    val state = mutableState.asStateFlow()
    private val mutableFailureMessage = MutableStateFlow<String?>(null)
    val failureMessage = mutableFailureMessage.asStateFlow()
    private val mutableFailureCode = MutableStateFlow<String?>(null)
    val failureCode = mutableFailureCode.asStateFlow()
    private val mutableStartupStage = MutableStateFlow(StartupStage.Idle)
    val startupStage = mutableStartupStage.asStateFlow()
    private val mutableDiagnosticReport = MutableStateFlow<String?>(null)
    val diagnosticReport = mutableDiagnosticReport.asStateFlow()
    private val mutableStopped = MutableStateFlow(true)
    internal val stopped = mutableStopped.asStateFlow()
    @Volatile
    private var activeLifecycleAttempt: LifecycleAttempt? = null

    fun start(context: Context, configPath: String = SecureProfileStore.SING_BOX) {
      NativeRuntimeState.requireLibbox()
      AndroidTunnelLifecycleOwner.startSingBox(context, configPath)
    }

    internal fun startEngine(
      context: Context,
      configPath: String,
      attempt: LifecycleAttempt,
    ) {
      NativeRuntimeState.requireLibbox()
      activeLifecycleAttempt = attempt
      mutableStopped.value = false
      mutableState.value = ConnectionState.Connecting
      val intent = Intent(context, VeilarkVpnService::class.java)
        .setAction(ACTION_START)
        .putExtra(EXTRA_CONFIG_PATH, configPath)
        .putExtra(EXTRA_ATTEMPT_ID, attempt.attemptId)
        .putExtra(EXTRA_ENGINE_EPOCH, attempt.engineEpoch)
      try {
        ContextCompat.startForegroundService(context, intent)
      } catch (failure: Throwable) {
        mutableState.value = ConnectionState.Failed
        mutableStopped.value = true
        clearActiveAttempt(attempt)
        throw failure
      }
    }

    fun stop(context: Context) {
      mutableFailureMessage.value = null
      mutableFailureCode.value = null
      mutableDiagnosticReport.value = null
      AndroidTunnelLifecycleOwner.stop(context)
    }

    internal fun stopEngine(context: Context, attempt: LifecycleAttempt? = null) {
      if (attempt != null && activeLifecycleAttempt != attempt) return
      context.startService(
        Intent(context, VeilarkVpnService::class.java).setAction(ACTION_STOP),
      )
    }

    @Synchronized
    private fun clearActiveAttempt(attempt: LifecycleAttempt?) {
      if (activeLifecycleAttempt == attempt) activeLifecycleAttempt = null
    }
  }
}

private class StringArray(values: List<String>) : StringIterator {
  private val count = values.size
  private val iterator = values.iterator()
  override fun len(): Int = count
  override fun hasNext(): Boolean = iterator.hasNext()
  override fun next(): String = iterator.next()
}

private class NetworkInterfaceArray(
  values: List<BoxNetworkInterface>,
) : NetworkInterfaceIterator {
  private val iterator = values.iterator()
  override fun hasNext(): Boolean = iterator.hasNext()
  override fun next(): BoxNetworkInterface = iterator.next()
}

private inline fun io.nekohasekai.libbox.RoutePrefixIterator.forEachRemaining(
  block: (io.nekohasekai.libbox.RoutePrefix) -> Unit,
) {
  while (hasNext()) block(next())
}

private inline fun StringIterator.forEachRemaining(block: (String) -> Unit) {
  while (hasNext()) block(next())
}
