package com.example.veilark.session

import com.example.veilark.engine.BundledPaths
import com.example.veilark.engine.PrivilegedHelper
import com.example.veilark.engine.TunnelController
import com.example.veilark.engine.TunnelEngineKind
import com.example.veilark.engine.TunnelStatus
import com.example.veilark.profile.ConnectionNode
import com.example.veilark.profile.GeoRoutingRepository
import com.example.veilark.profile.GeoRoutingStore
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.profile.SingBoxCatalog
import com.example.veilark.profile.SingBoxCatalogEntry
import com.example.veilark.profile.SubscriptionFetcher
import com.example.veilark.profile.SubscriptionOrigin
import com.example.veilark.profile.SubscriptionParser
import com.example.veilark.protocol.TrustTunnelCatalog
import com.example.veilark.protocol.TrustTunnelCatalogEntry
import com.example.veilark.protocol.GeoIpRuCatalog
import com.example.veilark.protocol.GeoSiteRuCatalog
import com.example.veilark.protocol.TrustTunnelProfile
import com.example.veilark.storage.EncryptedStore
import com.example.veilark.storage.MacKeychain
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant

enum class LogLevel {
  INFO,
  WARNING,
  ERROR,
}

data class LogEntry(
  val at: Instant = Instant.now(),
  val message: String,
  val level: LogLevel = LogLevel.INFO,
  val component: String = "app",
  val code: String? = null,
)

class VeilarkSession(
  private val store: EncryptedStore,
  private val helper: TunnelController = PrivilegedHelper(BundledPaths.resolve()),
  private val healthChecker: ConnectionHealthChecker = NetworkHealthProbe,
  private val defaultRouteFingerprintProvider: DefaultRouteFingerprintProvider =
    MacDefaultRouteFingerprintProvider,
  private val geoRepository: GeoRoutingStore = GeoRoutingRepository(),
  private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  private val operationMutex = Mutex()
  @Volatile private var stopRequested = false
  private var appliedDefaultRoute: DefaultRouteFingerprint? = null
  private var recoveryAttempts = 0
  private var recoveryAfterMillis = 0L
  val recoveryPending: Boolean
    get() = status == TunnelStatus.DEGRADED && recoveryAttempts < MAX_RECOVERY_ATTEMPTS
  var engine by mutableStateOf(TunnelEngineKind.TRUST_TUNNEL)
  var status by mutableStateOf(TunnelStatus.DISCONNECTED)
    private set
  var statusDetail by mutableStateOf("")
    private set
  var singBoxEntries by mutableStateOf(emptyList<SingBoxCatalogEntry>())
    private set
  var trustEntries by mutableStateOf(emptyList<TrustTunnelCatalogEntry>())
    private set
  var selectedSingBoxId by mutableStateOf<String?>(null)
  var selectedTrustId by mutableStateOf<String?>(null)
  var logs by mutableStateOf(emptyList<LogEntry>())
    private set
  var routingMode by mutableStateOf(ProfileSelection.ROUTING_ALL)
    private set
  private var singBoxRoutingMode = ProfileSelection.ROUTING_ALL
  private var trustRoutingMode = ProfileSelection.ROUTING_ALL
  private var singBoxManualDirectEntries = ""
  private var singBoxManualVpnEntries = ""
  private var trustManualDirectEntries = ""
  private var trustManualVpnEntries = ""
  var manualDirectEntries by mutableStateOf("")
    private set
  var manualVpnEntries by mutableStateOf("")
    private set
  var lastHealthDetail by mutableStateOf("")
    private set
  var storageWarning by mutableStateOf<String?>(null)
    private set
  var busy by mutableStateOf(false)
    private set

  init {
    singBoxEntries = runCatching {
      if (store.exists(EncryptedStore.SING_BOX_CATALOG)) {
        SingBoxCatalog.decode(store.load(EncryptedStore.SING_BOX_CATALOG))
      } else {
        emptyList()
      }
    }.onFailure {
      storageWarning = RuntimeMessages.readSingBoxFailed
    }.getOrDefault(emptyList())
    trustEntries = runCatching {
      if (store.exists(EncryptedStore.TRUST_TUNNEL_CATALOG)) {
        TrustTunnelCatalog.decode(store.load(EncryptedStore.TRUST_TUNNEL_CATALOG))
      } else {
        emptyList()
      }
    }.onFailure {
      storageWarning = RuntimeMessages.readTrustFailed
    }.getOrDefault(emptyList())
    val preferences = runCatching {
      if (store.exists(EncryptedStore.PREFERENCES)) {
        JSONObject(store.load(EncryptedStore.PREFERENCES))
      } else {
        JSONObject()
      }
    }.getOrElse {
      storageWarning = RuntimeMessages.readPreferencesFailed
      JSONObject()
    }
    engine = runCatching {
      TunnelEngineKind.valueOf(preferences.optString("engine"))
    }.getOrDefault(TunnelEngineKind.TRUST_TUNNEL)
    selectedSingBoxId = preferences.optString("selectedSingBoxId")
      .takeIf { id -> singBoxEntries.any { it.id == id } }
      ?: singBoxEntries.firstOrNull()?.id
    selectedTrustId = preferences.optString("selectedTrustId")
      .takeIf { id -> trustEntries.any { it.id == id } }
      ?: trustEntries.firstOrNull()?.id
    val legacyRoutingMode = preferences.optString("routingMode")
      .takeIf {
        it in setOf(
          ProfileSelection.ROUTING_ALL,
          ProfileSelection.ROUTING_MANUAL,
          ProfileSelection.ROUTING_RU_DIRECT,
        )
      }
      ?: ProfileSelection.ROUTING_ALL
    singBoxRoutingMode = preferences.optString("singBoxRoutingMode")
      .takeIf {
        it in setOf(
          ProfileSelection.ROUTING_ALL,
          ProfileSelection.ROUTING_MANUAL,
          ProfileSelection.ROUTING_RU_DIRECT,
        )
      }
      ?: legacyRoutingMode
    trustRoutingMode = preferences.optString("trustRoutingMode")
      .takeIf {
        it in setOf(
          ProfileSelection.ROUTING_ALL,
          ProfileSelection.ROUTING_MANUAL,
          ProfileSelection.ROUTING_RU_DIRECT,
        )
      }
      ?: legacyRoutingMode
      ?: ProfileSelection.ROUTING_ALL
    routingMode = if (engine == TunnelEngineKind.SING_BOX) {
      singBoxRoutingMode
    } else {
      trustRoutingMode
    }
    val legacyManualDirect = preferences.optString("manualDirectEntries")
    val legacyManualVpn = preferences.optString("manualVpnEntries")
    singBoxManualDirectEntries = preferences.optString("singBoxManualDirectEntries")
      .ifBlank { legacyManualDirect }
    singBoxManualVpnEntries = preferences.optString("singBoxManualVpnEntries")
      .ifBlank { legacyManualVpn }
    trustManualDirectEntries = preferences.optString("trustManualDirectEntries")
    trustManualVpnEntries = preferences.optString("trustManualVpnEntries")
    manualDirectEntries = if (engine == TunnelEngineKind.SING_BOX) {
      singBoxManualDirectEntries
    } else {
      trustManualDirectEntries
    }
    manualVpnEntries = if (engine == TunnelEngineKind.SING_BOX) {
      singBoxManualVpnEntries
    } else {
      trustManualVpnEntries
    }
  }

  fun helperReady(): Boolean = helper.installed()

  fun enginePresent(kind: TunnelEngineKind = engine): Boolean = helper.enginePresent(kind)

  fun geoRuleSetsPresent(): Boolean = runCatching {
    geoRepository.currentOrBundled().let { it.geoIpSrs.isFile && it.geoSiteSrs.isFile }
  }.getOrDefault(false)

  fun geoIpRuPresent(): Boolean = runCatching {
    geoRepository.currentOrBundled().let { it.geoIpJson.isFile && it.geoSiteJson.isFile }
  }.getOrDefault(false)

  suspend fun refreshGeoData() {
    check(status == TunnelStatus.DISCONNECTED) { RuntimeMessages.disconnectBeforeRouting }
    // GEO downloads and decompilation happen in a private staging generation.
    // They must not hold the tunnel-operation mutex: connect can safely keep
    // using the immutable active/bundled generation while a refresh is slow.
    runCatching { geoRepository.refreshFromRemote() }
      .onSuccess {
        log(RuntimeMessages.geoUpdated, component = "geo", code = "GEO_UPDATE_OK")
      }
      .onFailure {
        log(
          RuntimeMessages.geoUpdateFailed,
          level = LogLevel.ERROR,
          component = "geo",
          code = "GEO_UPDATE_FAILED",
        )
      }
      .getOrElse { throw IllegalStateException(RuntimeMessages.geoUpdateFailed, it) }
  }

  suspend fun installHelper(): Result<Unit> = exclusiveOperation {
    check(!status.blocksOfflineChanges) {
      RuntimeMessages.disconnectBeforeHelper
    }
    withContext(Dispatchers.IO) { helper.install() }
      .onSuccess {
        log(RuntimeMessages.helperInstalled, component = "helper", code = "HELPER_INSTALLED")
      }
      .onFailure {
        log(
          RuntimeMessages.helperInstallFailed(it.message),
          level = LogLevel.ERROR,
          component = "helper",
          code = "HELPER_INSTALL_FAILED",
        )
      }
  }

  suspend fun importText(raw: String) {
    exclusiveOperation {
      check(!status.blocksOfflineChanges) {
        RuntimeMessages.disconnectBeforeImport
      }
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) {
        log(RuntimeMessages.emptyImport, level = LogLevel.WARNING, component = "subscription")
        error(RuntimeMessages.emptyImport)
      }
      val payload = if (trimmed.startsWith("https://", ignoreCase = true)) {
        withContext(Dispatchers.IO) {
          SubscriptionFetcher.fetch(trimmed, SubscriptionFetcher.desktopHeaders(trimmed))
        }
      } else {
        trimmed.toByteArray()
      }
      importPayload(payload, trimmed.takeIf { it.startsWith("https://", ignoreCase = true) })
    }
  }

  suspend fun importFile(file: File) {
    exclusiveOperation {
      check(!status.blocksOfflineChanges) {
        RuntimeMessages.disconnectBeforeImport
      }
      importPayload(withContext(Dispatchers.IO) { file.readBytes() }, sourceUrl = null)
    }
  }

  private fun importPayload(
    payload: ByteArray,
    sourceUrl: String?,
    expectedEngine: TunnelEngineKind? = null,
  ) {
    runCatching {
      val parser = SubscriptionParser()
      val trustLinks = parser.extractTrustTunnelLinks(payload)
      val asText = payload.decodeToString().trim()
      if (asText.startsWith("tt://", ignoreCase = true) || trustLinks.isNotEmpty()) {
        require(expectedEngine == null || expectedEngine == TunnelEngineKind.TRUST_TUNNEL) {
          RuntimeMessages.subscriptionEngineMismatch
        }
        val links = trustLinks.ifEmpty {
          asText.lineSequence().map(String::trim).filter { it.startsWith("tt://", ignoreCase = true) }.toList()
        }
        val compiled = links.map(TrustTunnelProfile::compile)
        val localIdentity = sourceUrl ?: compiled.joinToString("\n") { it.config }
        val importedSourceId = TrustTunnelCatalog.sourceId(sourceUrl, localIdentity)
        trustEntries = TrustTunnelCatalog.replaceSourceEntries(
          entries = trustEntries,
          profiles = compiled,
          sourceUrl = sourceUrl,
          origin = if (sourceUrl == null) SubscriptionOrigin.MANUAL else SubscriptionOrigin.REMOTE,
          localIdentity = localIdentity,
        )
        selectedTrustId = trustEntries.firstOrNull { it.sourceId == importedSourceId }?.id
          ?: trustEntries.firstOrNull()?.id
        store.save(EncryptedStore.TRUST_TUNNEL_CATALOG, TrustTunnelCatalog.encode(trustEntries))
        activateEngine(TunnelEngineKind.TRUST_TUNNEL)
        persistPreferences()
        log(
          RuntimeMessages.trustImported(compiled.size),
          component = "subscription",
          code = "TRUST_IMPORT_OK",
        )
        return
      }
      require(expectedEngine == null || expectedEngine == TunnelEngineKind.SING_BOX) {
        RuntimeMessages.subscriptionEngineMismatch
      }
      val compiled = parser.compile(payload)
      val entry = SingBoxCatalog.create(
        config = compiled.json,
        nodes = compiled.nodes,
        selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
        sourceUrl = sourceUrl,
        suggestedName = compiled.displayName,
      )
      singBoxEntries = SingBoxCatalog.replaceSource(singBoxEntries, entry)
      selectedSingBoxId = entry.id
      store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(singBoxEntries))
      activateEngine(TunnelEngineKind.SING_BOX)
      persistPreferences()
      log(
        RuntimeMessages.singBoxImported(compiled.profileCount),
        component = "subscription",
        code = "SING_IMPORT_OK",
      )
    }.onFailure { failure ->
      log(
        RuntimeMessages.importFailed(failure.message),
        level = LogLevel.ERROR,
        component = "subscription",
        code = "SUBSCRIPTION_IMPORT_FAILED",
      )
    }.getOrElse { failure ->
      throw IllegalArgumentException(RuntimeMessages.importFailed(failure.message), failure)
    }
  }

  suspend fun refreshSelectedSubscription() {
    exclusiveOperation {
      check(!status.blocksOfflineChanges) {
        RuntimeMessages.disconnectBeforeRefresh
      }
      val selectedEngine = engine
      val sourceUrl = selectedRemoteSourceUrl(selectedEngine)
        ?: error(RuntimeMessages.noRemoteSource)
      try {
        val payload = withContext(Dispatchers.IO) {
          SubscriptionFetcher.fetch(sourceUrl, SubscriptionFetcher.desktopHeaders(sourceUrl))
        }
        importPayload(payload, sourceUrl, expectedEngine = selectedEngine)
        activateEngine(selectedEngine)
        persistPreferences()
        log(
          RuntimeMessages.subscriptionRefreshed,
          component = "subscription",
          code = "SUBSCRIPTION_REFRESH_OK",
        )
      } catch (failure: Exception) {
        log(
          RuntimeMessages.refreshFailed(failure.message),
          level = LogLevel.ERROR,
          component = "subscription",
          code = "SUBSCRIPTION_REFRESH_FAILED",
        )
        throw IllegalStateException(RuntimeMessages.refreshFailed(failure.message), failure)
      }
    }
  }

  fun deleteSelectedSubscription() {
    exclusiveOperationNow(RuntimeMessages.waitForOperation) {
      check(!status.blocksOfflineChanges) {
        RuntimeMessages.disconnectBeforeDelete
      }
      when (engine) {
        TunnelEngineKind.SING_BOX -> {
          val id = selectedSingBoxId ?: error(RuntimeMessages.subscriptionNotSelected)
          singBoxEntries = SingBoxCatalog.removeSource(singBoxEntries, id)
          selectedSingBoxId = singBoxEntries.firstOrNull()?.id
          store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(singBoxEntries))
        }
        TunnelEngineKind.TRUST_TUNNEL -> {
          val entry = selectedTrustEntry() ?: error(RuntimeMessages.subscriptionNotSelected)
          trustEntries = TrustTunnelCatalog.removeSource(trustEntries, entry.sourceId)
          selectedTrustId = trustEntries.firstOrNull()?.id
          store.save(EncryptedStore.TRUST_TUNNEL_CATALOG, TrustTunnelCatalog.encode(trustEntries))
        }
      }
      persistPreferences()
      log(RuntimeMessages.subscriptionDeleted, component = "subscription", code = "SUBSCRIPTION_DELETED")
    }
  }

  suspend fun connect() {
    exclusiveOperation {
      if (status == TunnelStatus.CONNECTED) return@exclusiveOperation
      recoveryAttempts = 0
      connectLocked(reconnecting = false, routeFingerprint = currentDefaultRouteFingerprint())
    }
  }

  private suspend fun connectLocked(
    reconnecting: Boolean,
    routeFingerprint: DefaultRouteFingerprint?,
  ) {
      if (stopRequested) {
        stopRequested = false
        status = TunnelStatus.DISCONNECTED
        statusDetail = ""
        log(RuntimeMessages.connectCancelled, component = "tunnel", code = "CONNECT_CANCELLED")
        return
      }
      stopRequested = false
      status = if (reconnecting) TunnelStatus.RECONNECTING else TunnelStatus.CONNECTING
      statusDetail = ""
      lastHealthDetail = ""
      log(
        if (reconnecting) RuntimeMessages.reconnecting else RuntimeMessages.connecting,
        component = "tunnel",
        code = if (reconnecting) "NETWORK_HANDOVER_RECONNECTING" else "CONNECT_START",
      )
      runCatching {
        if (!enginePresent()) error(RuntimeMessages.engineMissing)
        if (!helper.installed()) error(RuntimeMessages.installHelperFirst)
        runInterruptible(Dispatchers.IO) { helper.stop().getOrThrow() }
        throwIfStopRequested()
        val request = selectedStartRequest(routeFingerprint?.interfaceName)
        runInterruptible(Dispatchers.IO) {
          helper.start(request.kind, request.prepare())
            .onFailure { error(friendlyHelperError(it.message)) }.getOrThrow()
        }
        delay(1_200)
        throwIfStopRequested()
        if (runInterruptible(Dispatchers.IO) { helper.status() } != "connected") error(RuntimeMessages.engineDidNotStart)
        if (runInterruptible(Dispatchers.IO) { helper.tunFailed() }) {
          error(RuntimeMessages.routesFailed)
        }
        if (engine == TunnelEngineKind.SING_BOX && runInterruptible(Dispatchers.IO) { helper.outboundUnresolved() }) {
          error(RuntimeMessages.singBoxResolutionFailed)
        }
        val health = healthChecker.check()
        throwIfStopRequested()
        lastHealthDetail = health.detail
        if (!health.reachable) {
          log(
            RuntimeMessages.healthDegraded(health.detail),
            level = LogLevel.WARNING,
            component = "health",
            code = "CONNECT_HEALTH_DEGRADED",
          )
        }
        appliedDefaultRoute = routeFingerprint
        recoveryAttempts = 0
        status = TunnelStatus.CONNECTED
        log(
          RuntimeMessages.connected(engine.name, health.detail),
          component = "tunnel",
          code = "CONNECT_OK",
        )
      }.onFailure { failure ->
        val cleanup = withContext(NonCancellable + Dispatchers.IO) { helper.stop() }
        stopRequested = false
        if (cleanup.isFailure) {
          status = TunnelStatus.DEGRADED
          statusDetail = RuntimeMessages.tunnelStartFailed
          recoveryAttempts = MAX_RECOVERY_ATTEMPTS
          log(RuntimeMessages.disconnectFailed(statusDetail), level = LogLevel.ERROR,
            component = "tunnel", code = "DISCONNECT_FAILED")
        } else if (failure is ConnectionCancelledException || failure is CancellationException) {
          status = TunnelStatus.DISCONNECTED
          statusDetail = ""
          log(RuntimeMessages.connectCancelled, component = "tunnel", code = "CONNECT_CANCELLED")
        } else {
          if (reconnecting) {
            recoveryAttempts += 1
            recoveryAfterMillis = monotonicMillis() + (1_000L shl recoveryAttempts.coerceAtMost(5))
          }
          status = if (reconnecting) TunnelStatus.DEGRADED else TunnelStatus.FAILED
          statusDetail = if (reconnecting) {
            RuntimeMessages.handoverDegraded
          } else {
            RuntimeMessages.localizedFailure(failure.message, RuntimeMessages.tunnelStartFailed)
          }
          log(
            if (reconnecting) RuntimeMessages.handoverDegraded else RuntimeMessages.connectFailed(statusDetail),
            level = LogLevel.ERROR,
            component = "tunnel",
            code = if (reconnecting) "NETWORK_HANDOVER_DEGRADED" else "CONNECT_FAILED",
          )
        }
        if (failure is CancellationException) throw failure
      }
  }

  /** Requests a stop without blocking the tray/UI event thread. */
  suspend fun disconnect() {
    stopRequested = true
    if (!operationMutex.tryLock()) {
      log(RuntimeMessages.disconnectQueued, component = "tunnel", code = "DISCONNECT_QUEUED")
      return
    }
    try {
      disconnectLocked()
    } finally {
      operationMutex.unlock()
    }
  }

  /** Waits for an in-flight connect to finish cancelling before returning. Use for app shutdown. */
  suspend fun disconnectAwaited(): Result<Unit> {
    stopRequested = true
    return operationMutex.withLock { disconnectLocked() }
  }

  /** Explicit lifecycle hook for a caller that must not leave a managed engine running on quit. */
  suspend fun stopForQuit(): Result<Unit> = disconnectAwaited()

  suspend fun reconcileStatus() {
    if (status != TunnelStatus.CONNECTED || !operationMutex.tryLock()) return
    try {
      when (runInterruptible(Dispatchers.IO) { helper.status() }) {
        "connected" -> Unit
        "disconnected" -> markEngineExited()
        else -> markRuntimeUnknown()
      }
    } finally {
      operationMutex.unlock()
    }
  }

  /**
   * Rebuilds one existing tunnel when the physical default route changes.
   * The mutex serializes this with UI actions; repeated route events while a
   * reconnect is in flight are intentionally ignored rather than queued.
   */
  suspend fun reconcileDefaultRouteHandover(): Boolean {
    if (status != TunnelStatus.CONNECTED && !recoveryPending) return false
    if (status == TunnelStatus.DEGRADED && monotonicMillis() < recoveryAfterMillis) return false
    if (!operationMutex.tryLock()) return false
    busy = true
    try {
      val observed = currentDefaultRouteFingerprint() ?: return false
      val applied = appliedDefaultRoute
      if (applied == null) {
        appliedDefaultRoute = observed
        return false
      }
      if (observed == applied && status == TunnelStatus.CONNECTED) return false
      log("Default route changed; rebuilding tunnel", component = "network", code = "NETWORK_HANDOVER_DETECTED")
      // Take one fresh snapshot so a route-event burst binds the rebuilt config
      // to the newest physical interface instead of queueing stale reconnects.
      connectLocked(reconnecting = true, routeFingerprint = currentDefaultRouteFingerprint() ?: observed)
      return status == TunnelStatus.CONNECTED
    } finally {
      busy = false
      operationMutex.unlock()
    }
  }

  /** Advisory probe: tunnel state means the engine is alive, not that a chosen public URL responds. */
  suspend fun checkConnectionHealth(): NetworkHealth {
    return exclusiveOperation {
      require(status == TunnelStatus.CONNECTED) { RuntimeMessages.connectFirst }
      val observed = runInterruptible(Dispatchers.IO) { helper.status() }
      if (observed != "connected") {
        if (observed == "disconnected") markEngineExited() else markRuntimeUnknown()
        return@exclusiveOperation NetworkHealth(false, statusDetail)
      }
      val health = healthChecker.check()
      lastHealthDetail = health.detail
      log(
        RuntimeMessages.healthChecked(health.detail),
        level = if (health.reachable) LogLevel.INFO else LogLevel.WARNING,
        component = "health",
        code = if (health.reachable) "HEALTH_OK" else "HEALTH_DEGRADED",
      )
      health
    }
  }

  fun switchEngine(kind: TunnelEngineKind) {
    exclusiveOperationNow(RuntimeMessages.waitForOperation) {
      check(!status.blocksOfflineChanges) {
        RuntimeMessages.disconnectBeforeEngineSwitch
      }
      activateEngine(kind)
      persistPreferences()
    }
  }

  fun updateRouting(mode: String, directEntries: String, vpnEntries: String) {
    exclusiveOperationNow(RuntimeMessages.waitForOperation) {
      check(!status.blocksOfflineChanges) {
        RuntimeMessages.disconnectBeforeRouting
      }
      require(
        mode in setOf(
          ProfileSelection.ROUTING_ALL,
          ProfileSelection.ROUTING_MANUAL,
          ProfileSelection.ROUTING_RU_DIRECT,
        ),
      ) { RuntimeMessages.unknownRoutingMode }
      if (mode == ProfileSelection.ROUTING_MANUAL) {
        runCatching {
          if (engine == TunnelEngineKind.SING_BOX) {
            ProfileSelection.applyRouting(
              config = selectedSingBoxEntry()?.config
                ?: error(RuntimeMessages.chooseSingBoxForRouting),
              mode = mode,
              directEntries = directEntries,
              vpnEntries = vpnEntries,
            )
          } else {
            TrustTunnelProfile.applyManualRouting(
              selectedTrustEntry()?.config ?: error(RuntimeMessages.chooseTrust),
              directEntries,
              vpnEntries,
            )
          }
        }.getOrElse { failure ->
          throw IllegalArgumentException(
            RuntimeMessages.localizedFailure(failure.message, RuntimeMessages.unknownRoutingMode),
            failure,
          )
        }
      }
      routingMode = mode
      if (engine == TunnelEngineKind.SING_BOX) {
        singBoxRoutingMode = mode
        if (mode == ProfileSelection.ROUTING_MANUAL) {
          singBoxManualDirectEntries = directEntries.trim()
          singBoxManualVpnEntries = vpnEntries.trim()
        }
      } else {
        trustRoutingMode = mode
        if (mode == ProfileSelection.ROUTING_MANUAL) {
          trustManualDirectEntries = directEntries.trim()
          trustManualVpnEntries = vpnEntries.trim()
        }
      }
      manualDirectEntries = if (engine == TunnelEngineKind.SING_BOX) {
        singBoxManualDirectEntries
      } else {
        trustManualDirectEntries
      }
      manualVpnEntries = if (engine == TunnelEngineKind.SING_BOX) {
        singBoxManualVpnEntries
      } else {
        trustManualVpnEntries
      }
      persistPreferences()
      log(RuntimeMessages.routingUpdated, component = "routing", code = "ROUTING_UPDATED")
    }
  }

  fun selectSingBox(id: String, nodeTag: String? = null) {
    exclusiveOperationNow(RuntimeMessages.waitForOperation) {
      check(!status.blocksOfflineChanges) {
        RuntimeMessages.disconnectBeforeProfileSwitch
      }
      val entry = singBoxEntries.firstOrNull { it.id == id }
        ?: error(RuntimeMessages.singBoxProfileMissing)
      if (nodeTag != null) {
        require(nodeTag == ProfileSelection.AUTOMATIC_TAG || entry.nodes.any { it.tag == nodeTag }) {
          RuntimeMessages.serverMissing
        }
        singBoxEntries = singBoxEntries.map { candidate ->
          if (candidate.id == id) candidate.copy(selectedNodeTag = nodeTag) else candidate
        }
        store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(singBoxEntries))
      }
      selectedSingBoxId = id
      persistPreferences()
    }
  }

  fun selectTrust(id: String) {
    exclusiveOperationNow(RuntimeMessages.waitForOperation) {
      check(!status.blocksOfflineChanges) {
        RuntimeMessages.disconnectBeforeProfileSwitch
      }
      require(trustEntries.any { it.id == id }) { RuntimeMessages.trustProfileMissing }
      selectedTrustId = id
      persistPreferences()
    }
  }

  fun currentNodes(): List<ConnectionNode> = when (engine) {
    TunnelEngineKind.SING_BOX ->
      singBoxEntries.firstOrNull { it.id == selectedSingBoxId }?.nodes.orEmpty()
    TunnelEngineKind.TRUST_TUNNEL -> selectedTrustEntry()?.let { TrustTunnelCatalog.nodes(listOf(it)) }.orEmpty()
  }

  fun clearLogs() {
    logs = emptyList()
  }

  fun log(
    message: String,
    level: LogLevel = LogLevel.INFO,
    component: String = "app",
    code: String? = null,
  ) {
    logs = (logs + LogEntry(
      message = redactLogMessage(message),
      level = level,
      component = component.take(32),
      code = code?.take(64),
    ))
      .takeLast(500)
  }

  private suspend fun <T> exclusiveOperation(block: suspend () -> T): T = operationMutex.withLock {
    busy = true
    try {
      block()
    } finally {
      busy = false
    }
  }

  private fun <T> exclusiveOperationNow(waitMessage: String, block: () -> T): T {
    check(operationMutex.tryLock()) { waitMessage }
    busy = true
    try {
      return block()
    } finally {
      busy = false
      operationMutex.unlock()
    }
  }

  private fun selectedSingBoxEntry(): SingBoxCatalogEntry? =
    singBoxEntries.firstOrNull { it.id == selectedSingBoxId }

  private fun selectedTrustEntry(): TrustTunnelCatalogEntry? =
    trustEntries.firstOrNull { it.id == selectedTrustId }

  private fun activateEngine(kind: TunnelEngineKind) {
    if (engine == kind) return
    if (engine == TunnelEngineKind.SING_BOX) {
      singBoxRoutingMode = routingMode
      singBoxManualDirectEntries = manualDirectEntries
      singBoxManualVpnEntries = manualVpnEntries
    } else {
      trustRoutingMode = routingMode
      trustManualDirectEntries = manualDirectEntries
      trustManualVpnEntries = manualVpnEntries
    }
    engine = kind
    routingMode = if (kind == TunnelEngineKind.SING_BOX) {
      singBoxRoutingMode
    } else {
      trustRoutingMode
    }
    manualDirectEntries = if (kind == TunnelEngineKind.SING_BOX) {
      singBoxManualDirectEntries
    } else {
      trustManualDirectEntries
    }
    manualVpnEntries = if (kind == TunnelEngineKind.SING_BOX) {
      singBoxManualVpnEntries
    } else {
      trustManualVpnEntries
    }
  }

  private fun selectedRemoteSourceUrl(kind: TunnelEngineKind): String? = when (kind) {
    TunnelEngineKind.SING_BOX -> selectedSingBoxEntry()?.sourceUrl
    TunnelEngineKind.TRUST_TUNNEL -> selectedTrustEntry()?.sourceUrl
  }

  private data class StartRequest(val kind: TunnelEngineKind, val prepare: () -> String)

  /** Snapshot Compose-owned selection before dispatching configuration work to IO. */
  private fun selectedStartRequest(defaultInterface: String?): StartRequest {
    val kind = engine
    val mode = routingMode
    val direct = manualDirectEntries
    val vpn = manualVpnEntries
    return when (kind) {
      TunnelEngineKind.SING_BOX -> {
        val entry = selectedSingBoxEntry() ?: error(RuntimeMessages.chooseSingBox)
        StartRequest(kind) { prepareSingBox(entry, mode, direct, vpn, defaultInterface) }
      }
      TunnelEngineKind.TRUST_TUNNEL -> {
        val config = selectedTrustEntry()?.config ?: error(RuntimeMessages.chooseTrust)
        StartRequest(kind) { prepareTrustTunnel(config, mode, direct, vpn) }
      }
    }
  }

  private fun prepareSingBox(entry: SingBoxCatalogEntry, mode: String, direct: String, vpn: String,
    defaultInterface: String?): String {
    val selected = ProfileSelection.select(entry.config, entry.selectedNodeTag, entry.nodes)
    val routed = ProfileSelection.applyRouting(
      config = selected,
      mode = mode,
      directEntries = direct,
      vpnEntries = vpn,
      geoRuleSets = if (mode == ProfileSelection.ROUTING_RU_DIRECT) {
        val paths = geoRepository.currentOrBundled()
        require(paths.geoIpSrs.isFile && paths.geoSiteSrs.isFile) {
          RuntimeMessages.geoFilesMissing
        }
        ProfileSelection.GeoRuleSets(
          geoIpRuPath = paths.geoIpSrs.absolutePath,
          geoSiteRuPath = paths.geoSiteSrs.absolutePath,
        )
      } else {
        null
      },
    )
    return SubscriptionParser.migrateSingBoxForMac(routed, defaultInterface)
  }

  private fun prepareTrustTunnel(config: String, mode: String, direct: String, vpn: String): String {
    return if (mode == ProfileSelection.ROUTING_RU_DIRECT) {
      val paths = geoRepository.currentOrBundled()
      require(paths.geoIpJson.isFile && paths.geoSiteJson.isFile) {
        RuntimeMessages.geoFilesMissing
      }
      TrustTunnelProfile.applyGeoIpRuDirect(
        config,
        runCatching { GeoIpRuCatalog.load(paths.geoIpJson) }
          .getOrElse { error(RuntimeMessages.geoIpInvalid) },
        runCatching { GeoSiteRuCatalog.load(paths.geoSiteJson) }
          .getOrElse { error(RuntimeMessages.geoIpInvalid) },
      )
    } else if (mode == ProfileSelection.ROUTING_MANUAL) {
      TrustTunnelProfile.applyManualRouting(config, direct, vpn)
    } else {
      TrustTunnelProfile.prepareMacConfig(config)
    }
  }

  private fun throwIfStopRequested() {
    if (stopRequested) throw ConnectionCancelledException()
  }

  private suspend fun disconnectLocked(): Result<Unit> {
    val result = withContext(NonCancellable + Dispatchers.IO) { helper.stop() }
    stopRequested = false
    recoveryAttempts = 0
    if (result.isSuccess) {
      status = TunnelStatus.DISCONNECTED
      statusDetail = ""
      lastHealthDetail = ""
      appliedDefaultRoute = null
      log(RuntimeMessages.disconnected, component = "tunnel", code = "DISCONNECT_OK")
    } else {
      status = TunnelStatus.DEGRADED
      recoveryAttempts = MAX_RECOVERY_ATTEMPTS
      statusDetail = friendlyHelperError(result.exceptionOrNull()?.message)
      log(
        RuntimeMessages.disconnectFailed(statusDetail),
        level = LogLevel.ERROR,
        component = "tunnel",
        code = "DISCONNECT_FAILED",
      )
    }
    return result
  }

  private fun markEngineExited() {
    status = TunnelStatus.FAILED
    statusDetail = RuntimeMessages.engineExited
    log(
      statusDetail,
      level = LogLevel.ERROR,
      component = "tunnel",
      code = "ENGINE_EXITED",
    )
  }

  private fun markRuntimeUnknown() {
    status = TunnelStatus.DEGRADED
    recoveryAttempts = MAX_RECOVERY_ATTEMPTS
    statusDetail = RuntimeMessages.helperStateUnknown
    log(statusDetail, level = LogLevel.ERROR, component = "helper", code = "HELPER_STATUS_UNKNOWN")
  }

  private fun redactLogMessage(message: String): String = message
    .replace(SENSITIVE_LINK, "[redacted-link]")
    .replace(SENSITIVE_ASSIGNMENT, "${'$'}1=[redacted]")
    .take(MAX_LOG_MESSAGE_LENGTH)

  private fun persistPreferences() {
    runCatching {
      store.save(
        EncryptedStore.PREFERENCES,
        JSONObject()
          .put("version", 1)
          .put("engine", engine.name)
          .put("selectedSingBoxId", selectedSingBoxId ?: JSONObject.NULL)
          .put("selectedTrustId", selectedTrustId ?: JSONObject.NULL)
          .put("routingMode", routingMode)
          .put("singBoxRoutingMode", singBoxRoutingMode)
          .put("trustRoutingMode", trustRoutingMode)
          .put("manualDirectEntries", manualDirectEntries)
          .put("manualVpnEntries", manualVpnEntries)
          .put("singBoxManualDirectEntries", singBoxManualDirectEntries)
          .put("singBoxManualVpnEntries", singBoxManualVpnEntries)
          .put("trustManualDirectEntries", trustManualDirectEntries)
          .put("trustManualVpnEntries", trustManualVpnEntries)
          .toString(),
      )
      storageWarning = null
    }.onFailure {
      storageWarning = RuntimeMessages.savePreferencesFailed
    }
  }

  private fun friendlyHelperError(message: String?): String = when {
    message.isNullOrBlank() -> RuntimeMessages.tunnelStartFailed
    "not running as root" in message || "missing root" in message ->
      RuntimeMessages.helperNeedsRoot
    "config path not allowed" in message ->
      RuntimeMessages.configRejected
    "tunnel routes" in message -> RuntimeMessages.trustRoutesFailed
    "engine exited" in message -> {
      val log = helper.lastLog()
      when {
        "permission denied" in log || "SIOCAIFADDR" in log ->
          RuntimeMessages.helperNeedsRoot
        "Unable to setup routes" in log || "Failed to create listener" in log ->
          RuntimeMessages.trustRoutesFailed
        "empty direct outbound" in log ->
          RuntimeMessages.singBoxDnsRejected
        "empty result" in log ->
          RuntimeMessages.singBoxResolutionFailed
        else -> RuntimeMessages.engineExitedImmediately
      }
    }
    else -> RuntimeMessages.localizedFailure(message, RuntimeMessages.tunnelStartFailed)
  }

  private suspend fun currentDefaultRouteFingerprint(): DefaultRouteFingerprint? =
    withContext(Dispatchers.IO) { defaultRouteFingerprintProvider.current() }

  companion object {
    private const val MAX_RECOVERY_ATTEMPTS = 5
    private const val MAX_LOG_MESSAGE_LENGTH = 500
    private val SENSITIVE_LINK = Regex(
      """(?i)\b(?:https?|tt|vless|vmess|trojan|hysteria2?|ss)://[^\s]+""",
    )
    private val SENSITIVE_ASSIGNMENT = Regex(
      """(?i)\b(password|passwd|token|secret|uuid|authorization)\s*[:=]\s*[^\s,;]+""",
    )

    fun createDefault(onKeychainFailure: (Throwable) -> Unit = {}): VeilarkSession {
      val dir = File(System.getProperty("user.home"), "Library/Application Support/Veilark/secure")
      val key = runCatching { MacKeychain.loadOrCreateKey() }
      key.exceptionOrNull()?.let(onKeychainFailure)
      return VeilarkSession(EncryptedStore(dir) { key.getOrThrow() }).also { session ->
        if (key.isFailure) {
          session.storageWarning = RuntimeMessages.keychainUnavailable
        }
      }
    }
  }
}

private class ConnectionCancelledException : IllegalStateException(RuntimeMessages.connectCancelled)
