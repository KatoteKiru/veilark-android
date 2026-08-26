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
import java.io.File
import java.time.Instant

data class LogEntry(
  val at: Instant = Instant.now(),
  val message: String,
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
  var busy by mutableStateOf(false)
    private set

  init {
    singBoxEntries = runCatching {
      if (store.exists(EncryptedStore.SING_BOX_CATALOG)) {
        SingBoxCatalog.decode(store.load(EncryptedStore.SING_BOX_CATALOG))
      } else {
        emptyList()
      }
    }.getOrDefault(emptyList())
    trustEntries = runCatching {
      if (store.exists(EncryptedStore.TRUST_TUNNEL_CATALOG)) {
        TrustTunnelCatalog.decode(store.load(EncryptedStore.TRUST_TUNNEL_CATALOG))
      } else {
        emptyList()
      }
    }.getOrDefault(emptyList())
    selectedSingBoxId = singBoxEntries.firstOrNull()?.id
    selectedTrustId = trustEntries.firstOrNull()?.id
  }

  fun helperReady(): Boolean = helper.installed()

  fun enginesPresent(): Boolean {
    val paths = BundledPaths.resolve()
    return paths.singBox.isFile && paths.trustTunnel.isFile
  }

  fun installHelper(): Result<Unit> = helper.install().onSuccess {
    log("VPN helper установлен")
  }.onFailure {
    log("Helper: ${it.message}")
  }

  suspend fun importText(raw: String) {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
      log("Пустой импорт")
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
        trustEntries = TrustTunnelCatalog.replaceSourceEntries(
          entries = trustEntries,
          profiles = compiled,
          sourceUrl = sourceUrl,
          origin = if (sourceUrl == null) SubscriptionOrigin.MANUAL else SubscriptionOrigin.REMOTE,
          localIdentity = sourceUrl ?: compiled.joinToString("\n") { it.config },
        )
        selectedTrustId = trustEntries.firstOrNull()?.id
        store.save(EncryptedStore.TRUST_TUNNEL_CATALOG, TrustTunnelCatalog.encode(trustEntries))
        engine = TunnelEngineKind.TRUST_TUNNEL
        log("Импортировано TrustTunnel профилей: ${compiled.size}")
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
      log("Импортировано sing-box профилей: ${compiled.profileCount}")
    }.onFailure { failure ->
      log("Ошибка импорта: ${failure.message}")
    }.getOrThrow()
  }

  suspend fun connect() {
    status = TunnelStatus.CONNECTING
    statusDetail = ""
    busy = true
    log("Подключение…")
    runCatching {
      if (!enginesPresent()) error("Ядра sing-box/TrustTunnel не найдены")
      if (!helper.installed()) {
        helper.install().getOrThrow()
      }
      helper.stop()
      when (engine) {
        TunnelEngineKind.SING_BOX -> {
          val entry = singBoxEntries.firstOrNull { it.id == selectedSingBoxId }
            ?: singBoxEntries.firstOrNull()
            ?: error("Нет sing-box профиля")
          val selected = ProfileSelection.select(entry.config, entry.selectedNodeTag, entry.nodes)
          helper.start(
            TunnelEngineKind.SING_BOX,
            SubscriptionParser.migrateSingBoxForMac(selected, macDefaultInterface()),
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
      status = TunnelStatus.CONNECTED
      log("Туннель подключён (${engine.name})")
    }.onFailure { failure ->
      status = TunnelStatus.FAILED
      statusDetail = failure.message.orEmpty()
      log("Ошибка: ${failure.message}")
    }
    busy = false
  }

  fun disconnect() {
    helper.stop()
    status = TunnelStatus.DISCONNECTED
    log("Туннель отключён")
  }

  fun selectSingBox(id: String, nodeTag: String? = null) {
    selectedSingBoxId = id
    if (nodeTag != null) {
      singBoxEntries = singBoxEntries.map { entry ->
        if (entry.id == id) entry.copy(selectedNodeTag = nodeTag) else entry
      }
      store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(singBoxEntries))
    }
  }

  fun selectTrust(id: String) {
    selectedTrustId = id
  }

  fun currentNodes(): List<ConnectionNode> = when (engine) {
    TunnelEngineKind.SING_BOX ->
      singBoxEntries.firstOrNull { it.id == selectedSingBoxId }?.nodes.orEmpty()
    TunnelEngineKind.TRUST_TUNNEL -> TrustTunnelCatalog.nodes(trustEntries)
  }

  fun log(message: String) {
    logs = (logs + LogEntry(message = message)).takeLast(200)
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
        .getOrElse { EncryptedStore.ephemeralKey() }
      return VeilarkSession(EncryptedStore(dir) { key })
    }
  }
}
