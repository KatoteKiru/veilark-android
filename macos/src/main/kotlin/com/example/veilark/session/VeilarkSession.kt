package com.example.veilark.session

import com.example.veilark.engine.BundledPaths
import com.example.veilark.engine.PrivilegedHelper
import com.example.veilark.engine.TunnelEngineKind
import com.example.veilark.engine.TunnelStatus
import com.example.veilark.profile.ConnectionNode
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.profile.SingBoxCatalog
import com.example.veilark.profile.SingBoxCatalogEntry
import com.example.veilark.profile.SubscriptionFetcher
import com.example.veilark.profile.SubscriptionOrigin
import com.example.veilark.profile.SubscriptionParser
import com.example.veilark.protocol.TrustTunnelCatalog
import com.example.veilark.protocol.TrustTunnelCatalogEntry
import com.example.veilark.protocol.TrustTunnelProfile
import com.example.veilark.storage.EncryptedStore
import com.example.veilark.storage.MacKeychain
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
  private val helper: PrivilegedHelper = PrivilegedHelper(BundledPaths.resolve()),
) {
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
      storageWarning = "Не удалось прочитать защищённый каталог sing-box"
    }.getOrDefault(emptyList())
    trustEntries = runCatching {
      if (store.exists(EncryptedStore.TRUST_TUNNEL_CATALOG)) {
        TrustTunnelCatalog.decode(store.load(EncryptedStore.TRUST_TUNNEL_CATALOG))
      } else {
        emptyList()
      }
    }.onFailure {
      storageWarning = "Не удалось прочитать защищённый каталог TrustTunnel"
    }.getOrDefault(emptyList())
    val preferences = runCatching {
      if (store.exists(EncryptedStore.PREFERENCES)) {
        JSONObject(store.load(EncryptedStore.PREFERENCES))
      } else {
        JSONObject()
      }
    }.getOrElse {
      storageWarning = "Не удалось прочитать защищённые настройки"
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
    routingMode = preferences.optString("routingMode")
      .takeIf {
        it in setOf(
          ProfileSelection.ROUTING_ALL,
          ProfileSelection.ROUTING_MANUAL,
          ProfileSelection.ROUTING_RU_DIRECT,
        )
      }
      ?: ProfileSelection.ROUTING_ALL
    manualDirectEntries = preferences.optString("manualDirectEntries")
    manualVpnEntries = preferences.optString("manualVpnEntries")
  }

  fun helperReady(): Boolean = helper.installed()

  fun enginePresent(kind: TunnelEngineKind = engine): Boolean = helper.enginePresent(kind)

  fun geoRuleSetsPresent(): Boolean = BundledPaths.resolve().let {
    it.geoIpRu.isFile && it.geoSiteRu.isFile
  }

  suspend fun installHelper(): Result<Unit> = withContext(Dispatchers.IO) {
    helper.install()
  }.onSuccess {
    log("VPN helper установлен", component = "helper", code = "HELPER_INSTALLED")
  }.onFailure {
    log(
      "Helper: ${it.message}",
      level = LogLevel.ERROR,
      component = "helper",
      code = "HELPER_INSTALL_FAILED",
    )
  }

  suspend fun importText(raw: String) {
    check(status != TunnelStatus.CONNECTED && status != TunnelStatus.CONNECTING) {
      "Отключите VPN перед импортом подписки"
    }
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
      log("Пустой импорт", level = LogLevel.WARNING, component = "subscription")
      error("Пустой импорт")
    }
    busy = true
    try {
      val payload = if (trimmed.startsWith("https://", ignoreCase = true)) {
        withContext(Dispatchers.IO) {
          SubscriptionFetcher.fetch(trimmed, SubscriptionFetcher.desktopHeaders())
        }
      } else {
        trimmed.toByteArray()
      }
      importPayload(payload, trimmed.takeIf { it.startsWith("https://", ignoreCase = true) })
    } finally {
      busy = false
    }
  }

  suspend fun importFile(file: File) {
    check(status != TunnelStatus.CONNECTED && status != TunnelStatus.CONNECTING) {
      "Отключите VPN перед импортом подписки"
    }
    busy = true
    try {
      importPayload(withContext(Dispatchers.IO) { file.readBytes() }, sourceUrl = null)
    } finally {
      busy = false
    }
  }

  private fun importPayload(payload: ByteArray, sourceUrl: String?) {
    runCatching {
      val parser = SubscriptionParser()
      val trustLinks = parser.extractTrustTunnelLinks(payload)
      val asText = payload.decodeToString().trim()
      if (asText.startsWith("tt://", ignoreCase = true) || trustLinks.isNotEmpty()) {
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
        engine = TunnelEngineKind.TRUST_TUNNEL
        persistPreferences()
        log(
          "Импортировано TrustTunnel профилей: ${compiled.size}",
          component = "subscription",
          code = "TRUST_IMPORT_OK",
        )
        return
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
      engine = TunnelEngineKind.SING_BOX
      persistPreferences()
      log(
        "Импортировано sing-box профилей: ${compiled.profileCount}",
        component = "subscription",
        code = "SING_IMPORT_OK",
      )
    }.onFailure { failure ->
      log(
        "Ошибка импорта: ${failure.message}",
        level = LogLevel.ERROR,
        component = "subscription",
        code = "SUBSCRIPTION_IMPORT_FAILED",
      )
    }.getOrThrow()
  }

  suspend fun refreshSelectedSubscription() {
    check(status != TunnelStatus.CONNECTED && !busy) {
      "Отключите VPN перед обновлением подписки"
    }
    val selectedEngine = engine
    val sourceUrl = when (selectedEngine) {
      TunnelEngineKind.SING_BOX -> singBoxEntries
        .firstOrNull { it.id == selectedSingBoxId }
        ?.sourceUrl
      TunnelEngineKind.TRUST_TUNNEL -> trustEntries
        .firstOrNull { it.id == selectedTrustId }
        ?.sourceUrl
    } ?: error("У выбранного профиля нет HTTPS-ссылки для обновления")
    busy = true
    try {
      val payload = withContext(Dispatchers.IO) {
        SubscriptionFetcher.fetch(sourceUrl, SubscriptionFetcher.desktopHeaders())
      }
      importPayload(payload, sourceUrl)
      engine = selectedEngine
      persistPreferences()
      log(
        "Подписка обновлена",
        component = "subscription",
        code = "SUBSCRIPTION_REFRESH_OK",
      )
    } catch (failure: Exception) {
      log(
        "Обновление подписки: ${failure.message}",
        level = LogLevel.ERROR,
        component = "subscription",
        code = "SUBSCRIPTION_REFRESH_FAILED",
      )
      throw failure
    } finally {
      busy = false
    }
  }

  fun deleteSelectedSubscription() {
    check(status != TunnelStatus.CONNECTED && !busy) {
      "Отключите VPN перед удалением подписки"
    }
    when (engine) {
      TunnelEngineKind.SING_BOX -> {
        val id = selectedSingBoxId ?: error("Подписка не выбрана")
        singBoxEntries = SingBoxCatalog.removeSource(singBoxEntries, id)
        selectedSingBoxId = singBoxEntries.firstOrNull()?.id
        store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(singBoxEntries))
      }
      TunnelEngineKind.TRUST_TUNNEL -> {
        val entry = trustEntries.firstOrNull { it.id == selectedTrustId }
          ?: error("Подписка не выбрана")
        trustEntries = TrustTunnelCatalog.removeSource(trustEntries, entry.sourceId)
        selectedTrustId = trustEntries.firstOrNull()?.id
        store.save(EncryptedStore.TRUST_TUNNEL_CATALOG, TrustTunnelCatalog.encode(trustEntries))
      }
    }
    persistPreferences()
    log("Подписка удалена", component = "subscription", code = "SUBSCRIPTION_DELETED")
  }

  suspend fun connect() {
    if (busy || status == TunnelStatus.CONNECTING) return
    status = TunnelStatus.CONNECTING
    statusDetail = ""
    lastHealthDetail = ""
    busy = true
    log("Подключение…", component = "tunnel", code = "CONNECT_START")
    runCatching {
      if (!enginePresent()) error("Выбранное сетевое ядро не найдено")
      if (!helper.installed()) error("Сначала установите VPN helper в настройках")
      helper.stop().getOrThrow()
      when (engine) {
        TunnelEngineKind.SING_BOX -> {
          val entry = singBoxEntries.firstOrNull { it.id == selectedSingBoxId }
            ?: singBoxEntries.firstOrNull()
            ?: error("Нет sing-box профиля")
          val selected = ProfileSelection.select(entry.config, entry.selectedNodeTag, entry.nodes)
          val paths = BundledPaths.resolve()
          val routed = ProfileSelection.applyRouting(
            config = selected,
            mode = routingMode,
            directEntries = manualDirectEntries,
            vpnEntries = manualVpnEntries,
            geoRuleSets = if (routingMode == ProfileSelection.ROUTING_RU_DIRECT) {
              require(paths.geoIpRu.isFile && paths.geoSiteRu.isFile) {
                "Файлы геомаршрутизации не установлены"
              }
              ProfileSelection.GeoRuleSets(
                geoIpRuPath = paths.geoIpRu.absolutePath,
                geoSiteRuPath = paths.geoSiteRu.absolutePath,
              )
            } else {
              null
            },
          )
          helper.start(
            TunnelEngineKind.SING_BOX,
            SubscriptionParser.migrateSingBoxForMac(routed, macDefaultInterface()),
          ).onFailure { error(friendlyHelperError(it.message)) }.getOrThrow()
        }
        TunnelEngineKind.TRUST_TUNNEL -> {
          val entry = trustEntries.firstOrNull { it.id == selectedTrustId }
            ?: trustEntries.firstOrNull()
            ?: error("Нет TrustTunnel профиля")
          helper.start(
            TunnelEngineKind.TRUST_TUNNEL,
            TrustTunnelProfile.prepareMacConfig(entry.config),
          ).onFailure { error(friendlyHelperError(it.message)) }.getOrThrow()
        }
      }
      delay(1_200)
      if (helper.status() != "connected") {
        error("Ядро не запустилось")
      }
      if (helper.tunFailed()) {
        helper.stop()
        error("Туннель не поднял маршруты. Обновите helper (пароль macOS) и подключитесь снова.")
      }
      if (engine == TunnelEngineKind.SING_BOX && helper.outboundUnresolved()) {
        helper.stop()
        error("sing-box не смог разрешить адрес сервера. Обновите подписку или используйте TrustTunnel.")
      }
      val health = NetworkHealthProbe.check()
      lastHealthDetail = health.detail
      if (!health.reachable) {
        helper.stop()
        error("Туннель запущен, но проверка доступа в интернет не пройдена")
      }
      status = TunnelStatus.CONNECTED
      log(
        "Туннель подключён (${engine.name}); ${health.detail}",
        component = "tunnel",
        code = "CONNECT_OK",
      )
    }.onFailure { failure ->
      status = TunnelStatus.FAILED
      statusDetail = failure.message.orEmpty()
      log(
        "Ошибка: ${failure.message}",
        level = LogLevel.ERROR,
        component = "tunnel",
        code = "CONNECT_FAILED",
      )
    }
    busy = false
  }

  fun disconnect() {
    val result = helper.stop()
    busy = false
    if (result.isSuccess) {
      status = TunnelStatus.DISCONNECTED
      statusDetail = ""
      lastHealthDetail = ""
      log("Туннель отключён", component = "tunnel", code = "DISCONNECT_OK")
    } else {
      status = TunnelStatus.FAILED
      statusDetail = friendlyHelperError(result.exceptionOrNull()?.message)
      log(
        "Не удалось остановить туннель: $statusDetail",
        level = LogLevel.ERROR,
        component = "tunnel",
        code = "DISCONNECT_FAILED",
      )
    }
  }

  suspend fun reconcileStatus() {
    if (status != TunnelStatus.CONNECTED || busy) return
    val running = withContext(Dispatchers.IO) { helper.status() == "connected" }
    if (!running) {
      status = TunnelStatus.FAILED
      statusDetail = "Сетевое ядро неожиданно завершилось"
      log(
        statusDetail,
        level = LogLevel.ERROR,
        component = "tunnel",
        code = "ENGINE_EXITED",
      )
    }
  }

  fun switchEngine(kind: TunnelEngineKind) {
    check(status != TunnelStatus.CONNECTED && status != TunnelStatus.CONNECTING) {
      "Отключите VPN перед сменой ядра"
    }
    engine = kind
    persistPreferences()
  }

  fun updateRouting(mode: String, directEntries: String, vpnEntries: String) {
    check(status != TunnelStatus.CONNECTED && status != TunnelStatus.CONNECTING) {
      "Отключите VPN перед изменением маршрутизации"
    }
    require(
      mode in setOf(
        ProfileSelection.ROUTING_ALL,
        ProfileSelection.ROUTING_MANUAL,
        ProfileSelection.ROUTING_RU_DIRECT,
      ),
    ) { "Неизвестный режим маршрутизации" }
    if (mode == ProfileSelection.ROUTING_MANUAL) {
      ProfileSelection.applyRouting(
        config = singBoxEntries.firstOrNull()?.config
          ?: error("Добавьте sing-box подписку перед настройкой ручных правил"),
        mode = mode,
        directEntries = directEntries,
        vpnEntries = vpnEntries,
      )
    }
    routingMode = mode
    manualDirectEntries = directEntries.trim()
    manualVpnEntries = vpnEntries.trim()
    persistPreferences()
    log("Маршрутизация обновлена", component = "routing", code = "ROUTING_UPDATED")
  }

  fun selectSingBox(id: String, nodeTag: String? = null) {
    check(status != TunnelStatus.CONNECTED && status != TunnelStatus.CONNECTING) {
      "Отключите VPN перед сменой профиля"
    }
    selectedSingBoxId = id
    if (nodeTag != null) {
      singBoxEntries = singBoxEntries.map { entry ->
        if (entry.id == id) entry.copy(selectedNodeTag = nodeTag) else entry
      }
      store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(singBoxEntries))
    }
    persistPreferences()
  }

  fun selectTrust(id: String) {
    check(status != TunnelStatus.CONNECTED && status != TunnelStatus.CONNECTING) {
      "Отключите VPN перед сменой профиля"
    }
    selectedTrustId = id
    persistPreferences()
  }

  fun currentNodes(): List<ConnectionNode> = when (engine) {
    TunnelEngineKind.SING_BOX ->
      singBoxEntries.firstOrNull { it.id == selectedSingBoxId }?.nodes.orEmpty()
    TunnelEngineKind.TRUST_TUNNEL -> TrustTunnelCatalog.nodes(trustEntries)
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
    logs = (logs + LogEntry(message = message, level = level, component = component, code = code))
      .takeLast(500)
  }

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
          .put("manualDirectEntries", manualDirectEntries)
          .put("manualVpnEntries", manualVpnEntries)
          .toString(),
      )
      storageWarning = null
    }.onFailure {
      storageWarning = "Не удалось сохранить защищённые настройки"
    }
  }

  private fun friendlyHelperError(message: String?): String = when {
    message.isNullOrBlank() -> "Не удалось запустить туннель"
    "not running as root" in message || "missing root" in message ->
      "Helper без root. Нажмите «Установить VPN helper» ещё раз."
    "config path not allowed" in message ->
      "Путь к конфигу отклонён. Обновите helper (пароль macOS) и подключитесь снова."
    "tunnel routes" in message -> "TrustTunnel не смог настроить маршруты TUN"
    "engine exited" in message -> {
      val log = helper.lastLog()
      when {
        "permission denied" in log || "SIOCAIFADDR" in log ->
          "Helper без root. Нажмите «Установить VPN helper» ещё раз."
        "Unable to setup routes" in log || "Failed to create listener" in log ->
          "TrustTunnel не смог настроить маршруты TUN"
        "empty direct outbound" in log ->
          "sing-box отклонил DNS. Обновите приложение и подключитесь снова."
        "empty result" in log ->
          "sing-box не смог разрешить адрес сервера. Обновите подписку или используйте TrustTunnel."
        else -> "Ядро сразу завершилось"
      }
    }
    else -> message
  }

  private fun macDefaultInterface(): String? = runCatching {
    val text = ProcessBuilder("route", "-n", "get", "default")
      .redirectErrorStream(true)
      .start()
      .inputStream
      .bufferedReader()
      .readText()
    Regex("""(?m)^\s*interface:\s+(\S+)""").find(text)?.groupValues?.get(1)
      ?.takeIf { it.isNotBlank() && !it.startsWith("utun") }
  }.getOrNull()

  companion object {
    fun createDefault(): VeilarkSession {
      val dir = File(System.getProperty("user.home"), "Library/Application Support/Veilark/secure")
      val key = runCatching { MacKeychain.loadOrCreateKey() }
      return VeilarkSession(EncryptedStore(dir) { key.getOrThrow() }).also { session ->
        if (key.isFailure) {
          session.storageWarning = "Связка ключей macOS недоступна; изменения профилей заблокированы"
        }
      }
    }
  }
}
