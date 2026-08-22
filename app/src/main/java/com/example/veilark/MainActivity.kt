package com.example.veilark

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.veilark.theme.VeilarkTheme
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.diagnostics.TunnelDiagnostics
import com.example.veilark.ui.main.MainScreen
import com.example.veilark.ui.main.SubscriptionUiItem
import com.example.veilark.vpn.ConnectionState
import com.example.veilark.vpn.VeilarkVpnService
import com.example.veilark.profile.SubscriptionFetcher
import com.example.veilark.profile.SubscriptionParser
import com.example.veilark.profile.SubscriptionDeletionPolicy
import com.example.veilark.profile.SubscriptionSelectionPolicy
import com.example.veilark.profile.SubscriptionRefreshPolicy
import com.example.veilark.profile.ApplicationRoutingPolicy
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.profile.InstalledApp
import com.example.veilark.profile.InstalledAppLoader
import com.example.veilark.profile.SecureSubscriptionStore
import com.example.veilark.profile.SecureProfileStore
import com.example.veilark.profile.SingBoxCatalog
import com.example.veilark.update.AppUpdate
import com.example.veilark.update.UpdateManager
import com.example.veilark.vpn.LatencyMonitor
import com.example.veilark.vpn.EndpointLatencyProbe
import com.example.veilark.io.readAtMost
import com.example.veilark.protocol.ProfileEngine
import com.example.veilark.protocol.TrustTunnelManager
import com.example.veilark.protocol.TrustTunnelProfile
import com.example.veilark.protocol.TrustTunnelCatalog
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
  private var pendingConfigPath: String? = null
  private var pendingTrustConfig: String? = null
  private var pendingUpdateApk: File? = null
  private val tileConnectRequests = MutableStateFlow(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    consumeTileConnectIntent(intent)
    SecureProfileStore.migrateLegacy(this)
    migrateAutomaticRouting()
    val initialProfilePreferences = getSharedPreferences("profile_meta", MODE_PRIVATE)
    TrustTunnelCatalog.migrateSingle(
      this,
      initialProfilePreferences.getString("trust_display_name", null),
      SecureSubscriptionStore.loadTrust(this),
    )
    TrustTunnelCatalog.migrateLegacySource(this, SecureSubscriptionStore.loadTrust(this))
    SingBoxCatalog.migrateActive(
      this,
      initialProfilePreferences.getString(
        "sing_display_name",
        initialProfilePreferences.getString("display_name", null),
      ),
      ProfileSelection.decodeNodes(initialProfilePreferences.getString("nodes", null)),
      initialProfilePreferences.getString(
        "selected_node",
        ProfileSelection.AUTOMATIC_TAG,
      ),
      SecureSubscriptionStore.load(this),
    )
    reconcileStartupProfiles(initialProfilePreferences)

    enableEdgeToEdge()
    setContent {
      val profilePreferences = remember {
        getSharedPreferences("profile_meta", MODE_PRIVATE)
      }
      var profileEngine by remember {
        mutableStateOf(
          profilePreferences.getString("profile_engine", ProfileEngine.SING_BOX)
            ?: ProfileEngine.SING_BOX,
        )
      }
      var trustProfiles by remember {
        mutableStateOf(TrustTunnelCatalog.load(this))
      }
      var singBoxProfiles by remember {
        mutableStateOf(SingBoxCatalog.load(this))
      }
      var selectedSingBoxId by remember {
        val preferred = profilePreferences.getString("selected_sing_profile", null)
        mutableStateOf(
          preferred?.takeIf { id -> singBoxProfiles.any { it.id == id } }
            ?: singBoxProfiles.firstOrNull()?.id,
        )
      }
      var selectedTrustId by remember {
        val preferred = profilePreferences.getString("selected_trust_profile", null)
        mutableStateOf(
          preferred?.takeIf { id -> trustProfiles.any { it.id == id } }
            ?: trustProfiles.firstOrNull()?.id,
        )
      }
      var profileName by remember {
        mutableStateOf(
          if (
            (profileEngine == ProfileEngine.SING_BOX &&
              SecureProfileStore.exists(this, SecureProfileStore.SING_BOX)) ||
            (profileEngine == ProfileEngine.TRUST_TUNNEL &&
              SecureProfileStore.exists(this, SecureProfileStore.TRUST_TUNNEL))
          ) {
            if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
              trustProfiles.firstOrNull { it.id == selectedTrustId }?.name
                ?: profilePreferences.getString("trust_display_name", "TrustTunnel")
            } else {
              singBoxProfiles.firstOrNull { it.id == selectedSingBoxId }?.name
                ?: profilePreferences.getString(
                  "sing_display_name",
                  profilePreferences.getString("display_name", "Imported profile"),
                )
            }
          } else {
            null
          },
        )
      }
      var importError by remember { mutableStateOf<String?>(null) }
      var importing by remember { mutableStateOf(false) }
      var refreshingSubscription by remember { mutableStateOf(false) }
      var subscriptionRefreshAvailable by remember {
        mutableStateOf(
          if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
            trustProfiles.firstOrNull { it.id == selectedTrustId }?.sourceUrl != null
          } else {
            singBoxProfiles.firstOrNull { it.id == selectedSingBoxId }?.sourceUrl != null
          },
        )
      }
      var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
      var updateStatus by remember {
        mutableStateOf(if (BuildConfig.SELF_UPDATE_ENABLED) "Проверка обновлений…" else "")
      }
      var updating by remember { mutableStateOf(false) }
      var updateProgress by remember { mutableStateOf<Float?>(null) }
      var connectionNodes by remember {
        mutableStateOf(
          if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
            TrustTunnelCatalog.nodes(trustProfiles)
          } else {
            singBoxProfiles.firstOrNull { it.id == selectedSingBoxId }?.nodes
              ?: ProfileSelection.decodeNodes(profilePreferences.getString("nodes", null))
          },
        )
      }
      var selectedNodeTag by remember {
        mutableStateOf(
          if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
            selectedTrustId ?: ""
          } else {
            singBoxProfiles.firstOrNull { it.id == selectedSingBoxId }?.selectedNodeTag
              ?: profilePreferences.getString(
                "selected_node",
                ProfileSelection.AUTOMATIC_TAG,
              )
              ?: ProfileSelection.AUTOMATIC_TAG
          },
        )
      }
      var routingMode by remember {
        val storedMode = profilePreferences.getString(
          "routing_mode",
          ProfileSelection.ROUTING_ALL,
        )
        mutableStateOf(
          storedMode.takeIf {
            it == ProfileSelection.ROUTING_ALL || it == ProfileSelection.ROUTING_MANUAL
          } ?: ProfileSelection.ROUTING_ALL,
        )
      }
      var directRoutes by remember {
        mutableStateOf(profilePreferences.getString("direct_routes", "").orEmpty())
      }
      var vpnRoutes by remember {
        mutableStateOf(profilePreferences.getString("vpn_routes", "").orEmpty())
      }
      var applicationMode by remember {
        mutableStateOf(
          profilePreferences.getString("application_mode", ProfileSelection.APPS_ALL)
            ?: ProfileSelection.APPS_ALL,
        )
      }
      var dpiMode by remember {
        mutableStateOf(
          profilePreferences.getString("dpi_mode", ProfileSelection.DPI_OFF)
            ?: ProfileSelection.DPI_OFF,
        )
      }
      var selectedApplications by remember {
        mutableStateOf(
          profilePreferences.getStringSet("selected_applications", emptySet())?.toSet()
            ?: emptySet(),
        )
      }
      var installedApplications by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
      val coroutineScope = rememberCoroutineScope()
      val singBoxConnectionState by VeilarkVpnService.state.collectAsStateWithLifecycle()
      val trustTunnelConnectionState by TrustTunnelManager.state.collectAsStateWithLifecycle()
      val connectionState = if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
        trustTunnelConnectionState
      } else {
        singBoxConnectionState
      }
      // Profile replacement is transactional from the user's perspective: validate and persist
      // first, then stop every running engine immediately before switching the active profile.
      // This avoids a failed import needlessly dropping an otherwise healthy tunnel.
      val stopTunnelsAfterProfileCommit: () -> Unit = {
        if (trustTunnelConnectionState != ConnectionState.Disconnected) {
          TrustTunnelManager.stop(this@MainActivity)
        }
        if (singBoxConnectionState != ConnectionState.Disconnected) {
          VeilarkVpnService.stop(this@MainActivity)
        }
      }
      val connectionError by VeilarkVpnService.failureMessage.collectAsStateWithLifecycle()
      val trustTunnelError by TrustTunnelManager.failureMessage.collectAsStateWithLifecycle()
      val trustTunnelTransport by TrustTunnelManager.transport.collectAsStateWithLifecycle()
      val failureCode by VeilarkVpnService.failureCode.collectAsStateWithLifecycle()
      val singBoxStartupStage by VeilarkVpnService.startupStage.collectAsStateWithLifecycle()
      val startupStage = if (
        profileEngine == ProfileEngine.TRUST_TUNNEL &&
        connectionState == ConnectionState.Connecting
      ) {
        com.example.veilark.vpn.StartupStage.Core
      } else {
        singBoxStartupStage
      }
      val diagnosticReport by VeilarkVpnService.diagnosticReport.collectAsStateWithLifecycle()
      val technicalLogs by TechnicalLogStore.entries.collectAsStateWithLifecycle()
      val singBoxNodeLatencies by LatencyMonitor.latencies.collectAsStateWithLifecycle()
      val singBoxLatencyChecking by LatencyMonitor.checking.collectAsStateWithLifecycle()
      val endpointNodeLatencies by EndpointLatencyProbe.latencies.collectAsStateWithLifecycle()
      val endpointLatencyChecking by EndpointLatencyProbe.checking.collectAsStateWithLifecycle()
      val trustNodeLatencies by TrustTunnelManager.latencies.collectAsStateWithLifecycle()
      val trustLatencyChecking by
        TrustTunnelManager.latencyChecking.collectAsStateWithLifecycle()
      val nodeLatencies = if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
        trustNodeLatencies
      } else {
        endpointNodeLatencies + singBoxNodeLatencies
      }
      val latencyChecking = if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
        trustLatencyChecking
      } else {
        endpointLatencyChecking || singBoxLatencyChecking
      }
      val automaticNodeTag by LatencyMonitor.automaticSelection.collectAsStateWithLifecycle()
      val clipboard = LocalClipboardManager.current
      val mergeTrustTunnelLinks: (List<String>) -> Unit = { links ->
        if (links.isNotEmpty()) {
          val compiledProfiles = links.map(TrustTunnelProfile::compile)
          trustProfiles = TrustTunnelCatalog.upsert(this, compiledProfiles)
          val activeId = selectedTrustId?.takeIf { id ->
            trustProfiles.any { it.id == id }
          } ?: trustProfiles.first().id
          val active = TrustTunnelCatalog.activate(this, trustProfiles, activeId)
          selectedTrustId = active.id
          profilePreferences.edit()
            .putString("selected_trust_profile", active.id)
            .putString("trust_display_name", active.name)
            .apply()
          if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
            connectionNodes = TrustTunnelCatalog.nodes(trustProfiles)
            selectedNodeTag = active.id
            profileName = active.name
          }
        }
      }
      val reconcileRemoteTrustSource: (String, List<com.example.veilark.protocol.CompiledTrustTunnelProfile>) -> Unit =
        { sourceUrl, sourceProfiles ->
          val sourceId = TrustTunnelCatalog.sourceId(sourceUrl, sourceUrl)
          val selectedBefore = trustProfiles.firstOrNull { it.id == selectedTrustId }
          trustProfiles = if (sourceProfiles.isEmpty()) {
            if (trustProfiles.any { it.sourceId == sourceId }) {
              TrustTunnelCatalog.removeSource(this, sourceId)
            } else {
              trustProfiles
            }
          } else {
            TrustTunnelCatalog.replaceSource(
              context = this,
              profiles = sourceProfiles,
              sourceUrl = sourceUrl,
            )
          }
          val selectedAfter = selectedBefore?.let { previous ->
            trustProfiles.firstOrNull {
              it.sourceId == previous.sourceId && it.fingerprint == previous.fingerprint
            }
          } ?: trustProfiles.firstOrNull { it.id == selectedTrustId }
            ?: trustProfiles.firstOrNull { it.sourceId == sourceId }
            ?: trustProfiles.firstOrNull()
          if (selectedAfter == null) {
            SecureProfileStore.delete(this, SecureProfileStore.TRUST_TUNNEL)
            selectedTrustId = null
            profilePreferences.edit()
              .remove("selected_trust_profile")
              .remove("trust_display_name")
              .apply()
          } else {
            TrustTunnelCatalog.activate(this, trustProfiles, selectedAfter.id)
            selectedTrustId = selectedAfter.id
            profilePreferences.edit()
              .putString("selected_trust_profile", selectedAfter.id)
              .putString("trust_display_name", selectedAfter.name)
              .apply()
          }
          if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
            connectionNodes = TrustTunnelCatalog.nodes(trustProfiles)
            selectedNodeTag = selectedAfter?.id ?: ProfileSelection.AUTOMATIC_TAG
            profileName = selectedAfter?.name
            subscriptionRefreshAvailable = selectedAfter?.sourceUrl != null
          }
        }

      LaunchedEffect(Unit) {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return@LaunchedEffect
        if (!UpdateManager.shouldCheckAutomatically(this@MainActivity)) {
          updateStatus = "Установлена актуальная версия"
          return@LaunchedEffect
        }
        runCatching { UpdateManager.check() }
          .onSuccess { update ->
            availableUpdate = update
            updateStatus = if (update == null) {
              UpdateManager.markCurrentVersionChecked(this@MainActivity)
              "Установлена актуальная версия"
            } else {
              "Доступна версия ${update.versionName}"
            }
          }
          .onFailure {
            updateStatus = "Не удалось проверить обновления"
            TechnicalLogStore.warning("UPDATE", "Автоматическая проверка обновления не прошла")
          }
      }

      LaunchedEffect(connectionState) {
        if (
          profileEngine == ProfileEngine.SING_BOX &&
          connectionState == ConnectionState.Connected
        ) {
          LatencyMonitor.start()
        } else {
          LatencyMonitor.stop()
        }
      }

      val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
      ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
          pendingConfigPath?.let {
            if (trustTunnelConnectionState != ConnectionState.Disconnected) {
              TrustTunnelManager.stop(this)
            }
            VeilarkVpnService.start(this, it)
          }
          pendingTrustConfig?.let {
            if (singBoxConnectionState != ConnectionState.Disconnected) {
              VeilarkVpnService.stop(this)
            }
            TrustTunnelManager.start(this, it)
          }
        }
        pendingConfigPath = null
        pendingTrustConfig = null
      }

      var connectAfterNotificationPermission by remember { mutableStateOf(false) }
      val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
      ) { granted ->
        if (!granted) {
          TechnicalLogStore.warning(
            "NOTIFICATION",
            "Доступ к уведомлениям не предоставлен; состояние VPN останется в системном диспетчере",
          )
        }
        if (connectAfterNotificationPermission) {
          connectAfterNotificationPermission = false
          tileConnectRequests.value += 1
        }
      }

      val tileConnectRequest by tileConnectRequests.collectAsStateWithLifecycle()
      LaunchedEffect(tileConnectRequest) {
        if (tileConnectRequest == 0) return@LaunchedEffect
        if (shouldRequestNotificationPermission()) {
          markNotificationPermissionRequested()
          connectAfterNotificationPermission = true
          notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
          return@LaunchedEffect
        }
        runCatching {
          if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
            check(SecureProfileStore.exists(this@MainActivity, SecureProfileStore.TRUST_TUNNEL)) {
              "Профиль TrustTunnel не найден"
            }
            val config = SecureProfileStore.load(
              this@MainActivity,
              SecureProfileStore.TRUST_TUNNEL,
            )
            val permissionIntent = VpnService.prepare(this@MainActivity)
            if (permissionIntent == null) {
              TrustTunnelManager.start(this@MainActivity, config)
            } else {
              pendingTrustConfig = config
              vpnPermission.launch(permissionIntent)
            }
          } else {
            check(SecureProfileStore.exists(this@MainActivity, SecureProfileStore.SING_BOX)) {
              "Основной профиль не найден"
            }
            val permissionIntent = VpnService.prepare(this@MainActivity)
            if (permissionIntent == null) {
              VeilarkVpnService.start(this@MainActivity)
            } else {
              pendingConfigPath = SecureProfileStore.SING_BOX
              vpnPermission.launch(permissionIntent)
            }
          }
        }.onFailure {
          importError = it.message ?: "Не удалось запустить VPN"
          TechnicalLogStore.error("APP", "Запуск из панели быстрых настроек не выполнен")
        }
      }

      val installPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
      ) {
        pendingUpdateApk?.let { apk ->
          if (UpdateManager.canInstallPackages(this)) {
            UpdateManager.requestInstall(this, apk)
            pendingUpdateApk = null
          } else {
            updateStatus = "Разрешите установку обновлений для Veilark"
          }
        }
      }

      val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
      ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
          val config = contentResolver.openInputStream(uri)?.use { input ->
            input.readAtMost(MAX_CONFIG_SIZE + 1)
          } ?: error("Не удалось прочитать профиль")
          require(config.size <= MAX_CONFIG_SIZE) { "Профиль больше 2 МБ" }
          val configText = config.toString(Charsets.UTF_8)
          Libbox.checkConfig(configText)
          val importedName =
            uri.lastPathSegment?.substringAfterLast('/') ?: "Imported profile"
          val entry = SingBoxCatalog.create(
            config = configText,
            nodes = emptyList(),
            selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
            sourceUrl = null,
            suggestedName = importedName,
          )
          singBoxProfiles = SingBoxCatalog.upsert(this, entry)
          SingBoxCatalog.activate(this, entry)
          stopTunnelsAfterProfileCommit()
          selectedSingBoxId = entry.id
          profileName = entry.name
          profileEngine = ProfileEngine.SING_BOX
          connectionNodes = emptyList()
          selectedNodeTag = ProfileSelection.AUTOMATIC_TAG
          profilePreferences.edit()
            .putString("display_name", profileName)
            .putString("sing_display_name", profileName)
            .putString("selected_sing_profile", entry.id)
            .putString("profile_engine", profileEngine)
            .remove("nodes")
            .putString("selected_node", selectedNodeTag)
            .apply()
          subscriptionRefreshAvailable = false
          importError = null
        }.onFailure {
          importError = it.message ?: "Некорректная конфигурация"
          TechnicalLogStore.error("IMPORT", "Импорт JSON-конфигурации отклонён")
        }
      }

      var pendingQrConsumer by remember {
        mutableStateOf<((String) -> Unit)?>(null)
      }
      val qrScanner = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
      ) { result ->
        val value = result.data
          ?.getStringExtra(QrScannerActivity.EXTRA_RESULT)
          ?.trim()
          ?.takeIf(String::isNotEmpty)
        if (result.resultCode == Activity.RESULT_OK && value != null) {
          pendingQrConsumer?.invoke(value)
        } else {
          val message = result.data
            ?.getStringExtra(QrScannerActivity.EXTRA_ERROR)
            ?.takeIf(String::isNotBlank)
            ?: "Сканер камеры закрылся до чтения QR-кода"
          importError = message
          TechnicalLogStore.warning("IMPORT", "QR: $message")
        }
        pendingQrConsumer = null
      }
      val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
      ) { granted ->
        if (granted) {
          qrScanner.launch(Intent(this@MainActivity, QrScannerActivity::class.java))
        } else {
          importError = "Разрешите Veilark доступ к камере для чтения QR-кода"
          TechnicalLogStore.warning("QR", "Пользователь не разрешил доступ к камере")
          pendingQrConsumer = null
        }
      }

      VeilarkTheme {
        MainScreen(
          profileName = profileName,
          connectionState = connectionState,
          startupStage = startupStage,
          importError = importError ?: if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
            trustTunnelError
          } else {
            connectionError
          },
          failureCode = if (profileEngine == ProfileEngine.TRUST_TUNNEL &&
            trustTunnelError != null
          ) {
            "VPN-TRUST-CONNECTION"
          } else {
            failureCode
          },
          diagnosticReportAvailable = diagnosticReport != null,
          technicalLogs = technicalLogs,
          connectionNodes = connectionNodes,
          singBoxSubscriptions = singBoxProfiles,
          trustSubscriptions = TrustTunnelCatalog.sources(trustProfiles).map { source ->
            SubscriptionUiItem(
              id = source.id,
              name = source.name,
              nodeCount = source.nodeCount,
              refreshable = source.sourceUrl != null,
              deletable = source.origin != com.example.veilark.profile.SubscriptionOrigin.BUILT_IN,
            )
          },
          selectedSubscriptionId = selectedSingBoxId,
          selectedTrustSubscriptionId = trustProfiles
            .firstOrNull { it.id == selectedTrustId }
            ?.sourceId,
          selectedNodeTag = selectedNodeTag,
          nodeLatencies = nodeLatencies,
          automaticNodeTag = automaticNodeTag,
          automaticNodeSelectionAvailable = profileEngine == ProfileEngine.SING_BOX,
          latencyChecking = latencyChecking,
          routingMode = routingMode,
          directRoutes = directRoutes,
          vpnRoutes = vpnRoutes,
          applicationMode = applicationMode,
          dpiMode = dpiMode,
          selectedApplications = selectedApplications,
          installedApplications = installedApplications,
          routingAvailable = true,
          trustTunnelActive = profileEngine == ProfileEngine.TRUST_TUNNEL,
          singBoxAvailable = singBoxProfiles.isNotEmpty(),
          trustTunnelAvailable =
            SecureProfileStore.exists(this, SecureProfileStore.TRUST_TUNNEL),
          engineDescription = if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
            buildString {
              append("TrustTunnel 1.0.49")
              trustTunnelTransport?.let { append(" · $it") }
            }
          } else {
            "sing-box 1.13.14"
          },
          subscriptionRefreshAvailable = subscriptionRefreshAvailable,
          refreshingSubscription = refreshingSubscription,
          selfUpdateEnabled = BuildConfig.SELF_UPDATE_ENABLED,
          updateStatus = updateStatus,
          updateNotes = availableUpdate?.notes.orEmpty(),
          updateAvailable = availableUpdate != null,
          updating = updating,
          updateProgress = updateProgress,
          importing = importing,
          onImportFile = {
            filePicker.launch(arrayOf("application/json", "text/plain", "*/*"))
          },
          onScanQr = { onScanned ->
            pendingQrConsumer = onScanned
            importError = null
            if (
              ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.CAMERA,
              ) == PackageManager.PERMISSION_GRANTED
            ) {
              qrScanner.launch(Intent(this@MainActivity, QrScannerActivity::class.java))
            } else {
              cameraPermission.launch(Manifest.permission.CAMERA)
            }
          },
          onImportUrl = { url ->
            importing = true
            importError = null
            coroutineScope.launch {
              if (url.startsWith("tt://", ignoreCase = true)) {
                runCatching {
                  val compiled = withContext(Dispatchers.Default) {
                    TrustTunnelProfile.compile(url)
                  }
                  val importedSourceId = TrustTunnelCatalog.sourceId(null, compiled.config)
                  trustProfiles = TrustTunnelCatalog.replaceSource(
                    context = this@MainActivity,
                    profiles = listOf(compiled),
                    sourceUrl = null,
                  )
                  val active = trustProfiles.firstOrNull {
                    it.sourceId == importedSourceId
                  } ?: trustProfiles.firstOrNull()
                    ?: error("Не удалось сохранить профиль TrustTunnel")
                  TrustTunnelCatalog.activate(this@MainActivity, trustProfiles, active.id)
                  stopTunnelsAfterProfileCommit()
                  profileEngine = ProfileEngine.TRUST_TUNNEL
                  profileName = active.name
                  selectedTrustId = active.id
                  connectionNodes = TrustTunnelCatalog.nodes(trustProfiles)
                  selectedNodeTag = active.id
                  profilePreferences.edit()
                    .putString("display_name", compiled.displayName)
                    .putString("trust_display_name", compiled.displayName)
                    .putString("selected_trust_profile", active.id)
                    .putString("profile_engine", profileEngine)
                    .apply()
                  subscriptionRefreshAvailable = active.sourceUrl != null
                  TechnicalLogStore.info("IMPORT", "Профиль TrustTunnel добавлен")
                }.onFailure {
                  importError = it.message ?: "Не удалось импортировать TrustTunnel"
                  TechnicalLogStore.error("IMPORT", "TrustTunnel-ссылка отклонена")
                }
              } else {
                runCatching {
                  require(!url.startsWith("http://", ignoreCase = true)) {
                    "HTTP-подписки небезопасны. Используйте HTTPS-ссылку"
                  }
                  val payload = if (url.startsWith("https://", ignoreCase = true)) {
                    SubscriptionFetcher.fetch(
                      url,
                      SubscriptionFetcher.androidHeaders(this@MainActivity),
                    )
                  } else {
                    url.toByteArray(Charsets.UTF_8)
                  }
                  val parser = SubscriptionParser()
                  val trustLinks = parser.extractTrustTunnelLinks(payload)
                  val compiledAttempt = runCatching { parser.compile(payload) }
                  if (compiledAttempt.isFailure && trustLinks.isNotEmpty()) {
                    val trustCompiled = withContext(Dispatchers.Default) {
                      trustLinks.map(TrustTunnelProfile::compile)
                    }
                    val trustSourceId = TrustTunnelCatalog.sourceId(url, url)
                    trustProfiles = TrustTunnelCatalog.replaceSource(
                      context = this@MainActivity,
                      profiles = trustCompiled,
                      sourceUrl = url,
                    )
                    val active = trustProfiles.firstOrNull {
                      it.sourceId == trustSourceId
                    } ?: trustProfiles.firstOrNull()
                      ?: error("Не удалось сохранить подписку TrustTunnel")
                    TrustTunnelCatalog.activate(
                      this@MainActivity,
                      trustProfiles,
                      active.id,
                    )
                    stopTunnelsAfterProfileCommit()
                    profileEngine = ProfileEngine.TRUST_TUNNEL
                    profileName = active.name
                    selectedTrustId = active.id
                    connectionNodes = TrustTunnelCatalog.nodes(trustProfiles)
                    selectedNodeTag = active.id
                    profilePreferences.edit()
                      .putString("display_name", active.name)
                      .putString("trust_display_name", active.name)
                      .putString("selected_trust_profile", active.id)
                      .putString("profile_engine", profileEngine)
                      .apply()
                    subscriptionRefreshAvailable = active.sourceUrl != null
                    TechnicalLogStore.info(
                      "IMPORT",
                      "TrustTunnel-подписка импортирована, узлов: ${trustCompiled.size}",
                    )
                    return@runCatching null
                  }
                  val compiled = compiledAttempt.getOrThrow()
                  var config = ProfileSelection.applyRouting(
                    compiled.json,
                    routingMode,
                    directRoutes,
                    vpnRoutes,
                  )
                  config = ProfileSelection.applyApplications(
                    config,
                    applicationMode,
                    selectedApplications,
                    packageName,
                  )
                  config = ProfileSelection.applyDpiProtection(config, dpiMode)
                  Libbox.checkConfig(config)
                  // Validate mixed TrustTunnel links before committing the sing-box profile.
                  val compiledTrustProfiles = withContext(Dispatchers.Default) {
                    compiled.trustTunnelLinks.map(TrustTunnelProfile::compile)
                  }
                  val sourceUrl = url.takeIf {
                    it.startsWith("https://", ignoreCase = true)
                  }
                  val singEntry = SingBoxCatalog.create(
                    config = config,
                    nodes = compiled.nodes,
                    selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
                    sourceUrl = sourceUrl,
                    suggestedName = compiled.displayName,
                  )
                  singBoxProfiles = SingBoxCatalog.replaceSource(this@MainActivity, singEntry)
                  if (sourceUrl != null) {
                    reconcileRemoteTrustSource(sourceUrl, compiledTrustProfiles)
                  } else if (compiledTrustProfiles.isNotEmpty()) {
                    trustProfiles = TrustTunnelCatalog.upsert(
                      this@MainActivity,
                      compiledTrustProfiles,
                    )
                  }
                  SingBoxCatalog.activate(this@MainActivity, singEntry)
                  selectedSingBoxId = singEntry.id
                  stopTunnelsAfterProfileCommit()
                  profileEngine = ProfileEngine.SING_BOX
                  connectionNodes = compiled.nodes
                  selectedNodeTag = ProfileSelection.AUTOMATIC_TAG
                  profilePreferences.edit()
                    .putString("display_name", singEntry.name)
                    .putString("sing_display_name", singEntry.name)
                    .putString("selected_sing_profile", singEntry.id)
                    .putString("profile_engine", profileEngine)
                    .putString("nodes", ProfileSelection.encodeNodes(compiled.nodes))
                    .putString("selected_node", selectedNodeTag)
                    .apply()
                  if (url.startsWith("https://", ignoreCase = true)) {
                    subscriptionRefreshAvailable = true
                  } else {
                    subscriptionRefreshAvailable = false
                  }
                  TechnicalLogStore.info(
                    "IMPORT",
                    "Подписка импортирована, узлов: ${compiled.profileCount}",
                  )
                  compiled.rejectedReasons.forEach {
                    TechnicalLogStore.warning("IMPORT", it)
                  }
                  compiled
                }.onSuccess { compiled ->
                  if (compiled != null) {
                    profileName = SingBoxCatalog.find(
                      this@MainActivity,
                      selectedSingBoxId,
                    )?.name ?: compiled.displayName
                    importError = if (compiled.rejectedCount > 0) {
                      "Импортировано ${compiled.profileCount}; пропущено ${compiled.rejectedCount}"
                    } else {
                      null
                    }
                  }
                }.onFailure {
                  importError = it.message ?: "Не удалось импортировать подписку"
                  TechnicalLogStore.error("IMPORT", "Подписка не импортирована")
                }
              }
              importing = false
            }
          },
          onRefreshSubscription = {
            val refreshEngine = profileEngine
            val trustSnapshot = if (refreshEngine == ProfileEngine.TRUST_TUNNEL) {
              trustProfiles.firstOrNull { it.id == selectedTrustId }
            } else {
              null
            }
            val singSnapshot = if (refreshEngine == ProfileEngine.SING_BOX) {
              singBoxProfiles.firstOrNull { it.id == selectedSingBoxId }
            } else {
              null
            }
            val url = trustSnapshot?.sourceUrl ?: singSnapshot?.sourceUrl
            val refreshSourceId = trustSnapshot?.sourceId ?: singSnapshot?.id
            val refreshSelectedTag = selectedNodeTag
            val refreshRoutingMode = routingMode
            val refreshDirectRoutes = directRoutes
            val refreshVpnRoutes = vpnRoutes
            val refreshApplicationMode = applicationMode
            val refreshApplications = selectedApplications
            val refreshDpiMode = dpiMode
            if (url != null && refreshSourceId != null) {
              refreshingSubscription = true
              importError = null
              coroutineScope.launch {
                runCatching {
                  val payload = SubscriptionFetcher.fetch(
                    url,
                    SubscriptionFetcher.androidHeaders(this@MainActivity),
                  )
                  if (refreshEngine == ProfileEngine.TRUST_TUNNEL) {
                    val links = SubscriptionParser().extractTrustTunnelLinks(payload)
                    require(links.isNotEmpty()) {
                      "В подписке больше нет профилей TrustTunnel"
                    }
                    val refreshed = withContext(Dispatchers.Default) {
                      links.map(TrustTunnelProfile::compile)
                    }
                    require(
                      SubscriptionRefreshPolicy.canCommit(
                        refreshSourceId,
                        trustProfiles.map { it.sourceId },
                      ),
                    ) { "Подписка TrustTunnel была удалена до завершения обновления" }
                    val stillSelected = trustProfiles.firstOrNull { it.id == selectedTrustId }
                      ?.sourceId == refreshSourceId
                    trustProfiles = TrustTunnelCatalog.replaceSource(
                      context = this@MainActivity,
                      profiles = refreshed,
                      sourceUrl = url,
                    )
                    if (stillSelected) {
                      val active = trustProfiles.firstOrNull {
                        it.sourceId == refreshSourceId &&
                          it.fingerprint == trustSnapshot?.fingerprint
                      } ?: trustProfiles.firstOrNull {
                        it.sourceId == refreshSourceId
                      } ?: error("Обновлённый источник TrustTunnel пуст")
                      TrustTunnelCatalog.activate(
                        this@MainActivity,
                        trustProfiles,
                        active.id,
                      )
                      if (trustTunnelConnectionState != ConnectionState.Disconnected) {
                        TrustTunnelManager.stop(this@MainActivity)
                      }
                      selectedTrustId = active.id
                      if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
                        selectedNodeTag = active.id
                        profileName = active.name
                        connectionNodes = TrustTunnelCatalog.nodes(trustProfiles)
                      }
                      profilePreferences.edit()
                        .putString("display_name", active.name)
                        .putString("trust_display_name", active.name)
                        .putString("selected_trust_profile", active.id)
                        .apply()
                    }
                    TechnicalLogStore.info(
                      "SUBSCRIPTION",
                      "TrustTunnel-подписка обновлена, узлов: ${refreshed.size}",
                    )
                    return@runCatching
                  }
                  val compiled = SubscriptionParser().compile(payload)
                  val effectiveTag = refreshSelectedTag.takeIf { selected ->
                    selected == ProfileSelection.AUTOMATIC_TAG ||
                      compiled.nodes.any { it.tag == selected }
                  } ?: ProfileSelection.AUTOMATIC_TAG
                  var config = ProfileSelection.select(
                    compiled.json,
                    effectiveTag,
                    compiled.nodes,
                  )
                  config = ProfileSelection.applyRouting(
                    config,
                    refreshRoutingMode,
                    refreshDirectRoutes,
                    refreshVpnRoutes,
                  )
                  config = ProfileSelection.applyApplications(
                    config,
                    refreshApplicationMode,
                    refreshApplications,
                    packageName,
                  )
                  config = ProfileSelection.applyDpiProtection(config, refreshDpiMode)
                  Libbox.checkConfig(config)
                  val refreshedTrustProfiles = withContext(Dispatchers.Default) {
                    compiled.trustTunnelLinks.map(TrustTunnelProfile::compile)
                  }
                  require(
                    SubscriptionRefreshPolicy.canCommit(
                      refreshSourceId,
                      singBoxProfiles.map { it.id },
                    ),
                  ) { "Подписка была удалена до завершения обновления" }
                  val stillSelected = selectedSingBoxId == refreshSourceId
                  val refreshedEntry = SingBoxCatalog.create(
                    config = config,
                    nodes = compiled.nodes,
                    selectedNodeTag = effectiveTag,
                    sourceUrl = url,
                    suggestedName = singSnapshot?.name ?: compiled.displayName,
                  )
                  singBoxProfiles = SingBoxCatalog.replaceSource(
                    this@MainActivity,
                    refreshedEntry,
                  )
                  reconcileRemoteTrustSource(url, refreshedTrustProfiles)
                  if (stillSelected) {
                    SingBoxCatalog.activate(this@MainActivity, refreshedEntry)
                    if (singBoxConnectionState != ConnectionState.Disconnected) {
                      VeilarkVpnService.stop(this@MainActivity)
                    }
                    selectedSingBoxId = refreshedEntry.id
                    if (profileEngine == ProfileEngine.SING_BOX) {
                      connectionNodes = compiled.nodes
                      selectedNodeTag = effectiveTag
                      profileName = refreshedEntry.name
                    }
                    profilePreferences.edit()
                      .putString("display_name", refreshedEntry.name)
                      .putString("sing_display_name", refreshedEntry.name)
                      .putString("selected_sing_profile", refreshedEntry.id)
                      .putString("nodes", ProfileSelection.encodeNodes(compiled.nodes))
                      .putString("selected_node", effectiveTag)
                      .apply()
                  }
                  TechnicalLogStore.info(
                    "SUBSCRIPTION",
                    "Подписка обновлена, узлов: ${compiled.profileCount}",
                  )
                  compiled.rejectedReasons.forEach {
                    TechnicalLogStore.warning("SUBSCRIPTION", it)
                  }
                }.onFailure {
                  importError = it.message ?: "Не удалось обновить подписку"
                  TechnicalLogStore.error("SUBSCRIPTION", "Обновление подписки не выполнено")
                }
                refreshingSubscription = false
              }
            }
          },
          onSwitchProfile = {
            val target = if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
              ProfileEngine.SING_BOX
            } else {
              ProfileEngine.TRUST_TUNNEL
            }
            if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
              if (trustTunnelConnectionState != ConnectionState.Disconnected) {
                TrustTunnelManager.stop(this)
              }
            } else if (singBoxConnectionState != ConnectionState.Disconnected) {
              VeilarkVpnService.stop(this)
            }
            profileEngine = target
            TechnicalLogStore.info(
              "APP",
              "Выбран режим ${if (target == ProfileEngine.TRUST_TUNNEL) "TrustTunnel" else "sing-box"}",
            )
            if (target == ProfileEngine.TRUST_TUNNEL) {
              trustProfiles = TrustTunnelCatalog.load(this)
              val activeId = selectedTrustId?.takeIf { id ->
                trustProfiles.any { it.id == id }
              } ?: trustProfiles.firstOrNull()?.id
              val active = activeId?.let {
                TrustTunnelCatalog.activate(this, trustProfiles, it)
              }
              selectedTrustId = active?.id
              profileName = active?.name
              connectionNodes = TrustTunnelCatalog.nodes(trustProfiles)
              selectedNodeTag = active?.id.orEmpty()
            } else {
              singBoxProfiles = SingBoxCatalog.load(this)
              val active = SingBoxCatalog.find(this, selectedSingBoxId)
              active?.let { SingBoxCatalog.activate(this, it) }
              selectedSingBoxId = active?.id
              profileName = active?.name
              connectionNodes = active?.nodes.orEmpty()
              selectedNodeTag =
                active?.selectedNodeTag ?: ProfileSelection.AUTOMATIC_TAG
            }
            subscriptionRefreshAvailable = if (target == ProfileEngine.TRUST_TUNNEL) {
              trustProfiles.firstOrNull { it.id == selectedTrustId }?.sourceUrl != null
            } else {
              singBoxProfiles.firstOrNull { it.id == selectedSingBoxId }
                ?.sourceUrl != null
            }
            profilePreferences.edit().putString("profile_engine", target).apply()
            importError = null
          },
          onSelectNode = { tag ->
            runCatching {
              if (connectionState == ConnectionState.Connected ||
                connectionState == ConnectionState.Connecting
              ) {
                if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
                  TrustTunnelManager.stop(this)
                } else {
                  VeilarkVpnService.stop(this)
                }
              }
              if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
                val active = TrustTunnelCatalog.activate(this, trustProfiles, tag)
                selectedTrustId = active.id
                selectedNodeTag = active.id
                profileName = active.name
                subscriptionRefreshAvailable = active.sourceUrl != null
                profilePreferences.edit()
                  .putString("selected_trust_profile", active.id)
                  .putString("trust_display_name", active.name)
                  .apply()
              } else {
                val selected = ProfileSelection.select(
                  config = SecureProfileStore.load(this, SecureProfileStore.SING_BOX),
                  tag = tag,
                  nodes = connectionNodes,
                )
                Libbox.checkConfig(selected)
                SecureProfileStore.save(this, SecureProfileStore.SING_BOX, selected)
                singBoxProfiles.firstOrNull { it.id == selectedSingBoxId }?.let { current ->
                  singBoxProfiles = SingBoxCatalog.upsert(
                    this,
                    current.copy(config = selected, selectedNodeTag = tag),
                  )
                }
                selectedNodeTag = tag
                profilePreferences.edit().putString("selected_node", tag).apply()
              }
            }.onFailure {
              importError = it.message ?: "Не удалось выбрать узел"
              TechnicalLogStore.error("PROFILE", "Выбор узла не применён")
            }
          },
          onSelectSubscription = { id ->
            runCatching {
              if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
                if (trustTunnelConnectionState != ConnectionState.Disconnected) {
                  TrustTunnelManager.stop(this)
                }
                val entry = trustProfiles.firstOrNull { it.sourceId == id }
                  ?: error("Подписка TrustTunnel больше не найдена")
                TrustTunnelCatalog.activate(this, trustProfiles, entry.id)
                selectedTrustId = entry.id
                profileName = entry.name
                selectedNodeTag = entry.id
                connectionNodes = TrustTunnelCatalog.nodes(trustProfiles)
                subscriptionRefreshAvailable = entry.sourceUrl != null
                profilePreferences.edit()
                  .putString("selected_trust_profile", entry.id)
                  .putString("trust_display_name", entry.name)
                  .putString("display_name", entry.name)
                  .apply()
                TechnicalLogStore.info("PROFILE", "Выбрана подписка ${entry.name}")
              } else {
                if (singBoxConnectionState != ConnectionState.Disconnected) {
                  VeilarkVpnService.stop(this)
                }
                val entry = singBoxProfiles.firstOrNull { it.id == id }
                  ?: error("Подписка больше не найдена")
                SingBoxCatalog.activate(this, entry)
                selectedSingBoxId = entry.id
                profileName = entry.name
                connectionNodes = entry.nodes
                selectedNodeTag = entry.selectedNodeTag
                subscriptionRefreshAvailable = entry.sourceUrl != null
                profilePreferences.edit()
                  .putString("selected_sing_profile", entry.id)
                  .putString("sing_display_name", entry.name)
                  .putString("display_name", entry.name)
                  .putString("nodes", ProfileSelection.encodeNodes(entry.nodes))
                  .putString("selected_node", entry.selectedNodeTag)
                  .apply()
                TechnicalLogStore.info("PROFILE", "Выбрана подписка ${entry.name}")
              }
              importError = null
            }.onFailure {
              importError = it.message ?: "Не удалось переключить подписку"
              TechnicalLogStore.error("PROFILE", "Переключение подписки не выполнено")
            }
          },
          onDeleteSubscription = { sourceId ->
            runCatching {
              if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
                val removed = trustProfiles.filter { it.sourceId == sourceId }
                require(removed.isNotEmpty()) { "Подписка TrustTunnel больше не найдена" }
                removed.forEach { SubscriptionDeletionPolicy.requireDeletable(it.origin) }
                val activeSourceId = trustProfiles
                  .firstOrNull { it.id == selectedTrustId }
                  ?.sourceId
                val activeRemoved = SubscriptionDeletionPolicy.removesActiveSource(
                  activeSourceId,
                  sourceId,
                )
                if (activeRemoved &&
                  trustTunnelConnectionState != ConnectionState.Disconnected
                ) {
                  TrustTunnelManager.stop(this)
                }
                trustProfiles = TrustTunnelCatalog.removeSource(this, sourceId)
                if (activeRemoved) {
                  val fallback = trustProfiles.firstOrNull()
                  if (fallback == null) {
                    SecureProfileStore.delete(this, SecureProfileStore.TRUST_TUNNEL)
                    selectedTrustId = null
                    profileName = null
                    selectedNodeTag = ProfileSelection.AUTOMATIC_TAG
                    profilePreferences.edit()
                      .remove("selected_trust_profile")
                      .remove("trust_display_name")
                      .apply()
                  } else {
                    TrustTunnelCatalog.activate(this, trustProfiles, fallback.id)
                    selectedTrustId = fallback.id
                    profileName = fallback.name
                    selectedNodeTag = fallback.id
                    profilePreferences.edit()
                      .putString("selected_trust_profile", fallback.id)
                      .putString("trust_display_name", fallback.name)
                      .putString("display_name", fallback.name)
                      .apply()
                  }
                }
                connectionNodes = TrustTunnelCatalog.nodes(trustProfiles)
                subscriptionRefreshAvailable = trustProfiles
                  .firstOrNull { it.id == selectedTrustId }
                  ?.sourceUrl != null
              } else {
                require(singBoxProfiles.any { it.id == sourceId }) {
                  "Подписка больше не найдена"
                }
                val activeRemoved = SubscriptionDeletionPolicy.removesActiveSource(
                  selectedSingBoxId,
                  sourceId,
                )
                if (activeRemoved &&
                  singBoxConnectionState != ConnectionState.Disconnected
                ) {
                  VeilarkVpnService.stop(this)
                }
                singBoxProfiles = SingBoxCatalog.removeSource(this, sourceId)
                if (activeRemoved) {
                  val fallback = singBoxProfiles.firstOrNull()
                  if (fallback == null) {
                    SecureProfileStore.delete(this, SecureProfileStore.SING_BOX)
                    selectedSingBoxId = null
                    profileName = null
                    connectionNodes = emptyList()
                    selectedNodeTag = ProfileSelection.AUTOMATIC_TAG
                    profilePreferences.edit()
                      .remove("selected_sing_profile")
                      .remove("sing_display_name")
                      .remove("nodes")
                      .putString("selected_node", ProfileSelection.AUTOMATIC_TAG)
                      .apply()
                  } else {
                    SingBoxCatalog.activate(this, fallback)
                    selectedSingBoxId = fallback.id
                    profileName = fallback.name
                    connectionNodes = fallback.nodes
                    selectedNodeTag = fallback.selectedNodeTag
                    profilePreferences.edit()
                      .putString("selected_sing_profile", fallback.id)
                      .putString("sing_display_name", fallback.name)
                      .putString("display_name", fallback.name)
                      .putString("nodes", ProfileSelection.encodeNodes(fallback.nodes))
                      .putString("selected_node", fallback.selectedNodeTag)
                      .apply()
                  }
                }
                subscriptionRefreshAvailable = singBoxProfiles
                  .firstOrNull { it.id == selectedSingBoxId }
                  ?.sourceUrl != null
              }
              TechnicalLogStore.info("PROFILE", "Подписка удалена")
              importError = null
            }.onFailure {
              importError = it.message ?: "Не удалось удалить подписку"
              TechnicalLogStore.error("PROFILE", "Удаление подписки не выполнено")
            }
          },
          onCopyDiagnostic = {
            diagnosticReport?.let { clipboard.setText(AnnotatedString(it)) }
          },
          onClearTechnicalLogs = { TechnicalLogStore.clear() },
          onRunDiagnostics = {
            coroutineScope.launch {
              TunnelDiagnostics.run()
            }
          },
          onCheckUpdate = {
            updating = true
            updateStatus = "Проверка обновлений…"
            coroutineScope.launch {
              runCatching { UpdateManager.check() }
                .onSuccess { update ->
                  availableUpdate = update
                  updateStatus = if (update == null) {
                    UpdateManager.markCurrentVersionChecked(this@MainActivity)
                    "Установлена актуальная версия"
                  } else {
                    "Доступна версия ${update.versionName}"
                  }
                }
                .onFailure {
                  updateStatus = it.message ?: "Не удалось проверить обновления"
                  TechnicalLogStore.warning("UPDATE", "Ручная проверка обновления не прошла")
                }
              updating = false
            }
          },
          onRefreshLatency = {
            if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
              TrustTunnelManager.refreshLatencies(trustProfiles)
            } else if (connectionState == ConnectionState.Connected) {
              LatencyMonitor.refresh()
            } else {
              runCatching {
                EndpointLatencyProbe.refresh(
                  SecureProfileStore.load(this, SecureProfileStore.SING_BOX),
                  connectionNodes,
                )
              }.onFailure {
                TechnicalLogStore.error("PING", "Основной профиль недоступен")
              }
            }
          },
          onOpenRouting = {
            if (installedApplications.isEmpty()) {
              coroutineScope.launch {
                installedApplications = withContext(Dispatchers.IO) {
                  InstalledAppLoader.load(this@MainActivity)
                }
              }
            }
          },
          onApplyRouting = {
              newRoutingMode,
              newDirectRoutes,
              newVpnRoutes,
              newApplicationMode,
              newDpiMode,
              packages,
            ->
            runCatching {
              val applicationPolicy = ApplicationRoutingPolicy.fromPreferences(
                newApplicationMode,
                packages,
              )
              if (profileEngine == ProfileEngine.SING_BOX) {
                if (connectionState != ConnectionState.Disconnected &&
                  connectionState != ConnectionState.Failed
                ) {
                  VeilarkVpnService.stop(this)
                }
                var config = ProfileSelection.applyRouting(
                  SecureProfileStore.load(this, SecureProfileStore.SING_BOX),
                  newRoutingMode,
                  newDirectRoutes,
                  newVpnRoutes,
                )
                config = ProfileSelection.applyApplications(
                  config,
                  newApplicationMode,
                  packages,
                  packageName,
                )
                config = ProfileSelection.applyDpiProtection(config, newDpiMode)
                Libbox.checkConfig(config)
                SecureProfileStore.save(this, SecureProfileStore.SING_BOX, config)
                singBoxProfiles.firstOrNull { it.id == selectedSingBoxId }?.let { current ->
                  singBoxProfiles = SingBoxCatalog.upsert(
                    this,
                    current.copy(config = config),
                  )
                }
                routingMode = newRoutingMode
                directRoutes = newDirectRoutes.trim()
                vpnRoutes = newVpnRoutes.trim()
                dpiMode = newDpiMode
              } else {
                // Validate Android's mutually exclusive allow/disallow plan before persisting it.
                applicationPolicy.forTrustTunnel(packageName)
              }
              applicationMode = newApplicationMode
              selectedApplications = packages
              val routingEditor = profilePreferences.edit()
                .putString("application_mode", applicationMode)
                .putStringSet("selected_applications", selectedApplications)
              if (profileEngine == ProfileEngine.SING_BOX) {
                routingEditor
                  .putString("routing_mode", routingMode)
                  .putString("direct_routes", directRoutes)
                  .putString("vpn_routes", vpnRoutes)
                  .putString("dpi_mode", dpiMode)
              }
              check(routingEditor.commit()) { "Не удалось сохранить маршрутизацию приложений" }
              importError = null
            }.onFailure {
              importError = it.message ?: "Не удалось применить маршрутизацию"
              TechnicalLogStore.error("ROUTING", "Настройки маршрутизации отклонены")
            }
          },
          onUpdate = {
            val update = availableUpdate
            if (update != null) {
              updating = true
              updateProgress = 0f
              updateStatus = "Загрузка ${update.versionName}…"
              coroutineScope.launch {
                runCatching {
                  UpdateManager.download(this@MainActivity, update) { progress ->
                    withContext(Dispatchers.Main) {
                      updateProgress = progress.fraction
                      updateStatus = "Загрузка ${update.versionName} · " +
                        "${(progress.fraction * 100).toInt()}%"
                    }
                  }
                }
                  .onSuccess { apk ->
                    updateProgress = 1f
                    updateStatus = "Обновление загружено"
                    if (UpdateManager.canInstallPackages(this@MainActivity)) {
                      UpdateManager.requestInstall(this@MainActivity, apk)
                    } else {
                      pendingUpdateApk = apk
                      updateStatus = "Разрешите установку обновлений для Veilark"
                      installPermission.launch(
                        UpdateManager.installPermissionIntent(this@MainActivity),
                      )
                    }
                  }
                  .onFailure {
                    updateProgress = null
                    updateStatus = it.message ?: "Не удалось загрузить обновление"
                    TechnicalLogStore.error("UPDATE", "Загрузка APK не выполнена")
                  }
                updating = false
              }
            }
          },
          onConnect = {
            runCatching {
              if (connectionState == ConnectionState.Connected ||
                connectionState == ConnectionState.Connecting
              ) {
                if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
                  TrustTunnelManager.stop(this)
                } else {
                  VeilarkVpnService.stop(this)
                }
              } else if (shouldRequestNotificationPermission()) {
                markNotificationPermissionRequested()
                connectAfterNotificationPermission = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
              } else {
                if (profileEngine == ProfileEngine.TRUST_TUNNEL) {
                  if (singBoxConnectionState != ConnectionState.Disconnected) {
                    VeilarkVpnService.stop(this)
                  }
                  check(SecureProfileStore.exists(this, SecureProfileStore.TRUST_TUNNEL)) {
                    "Профиль TrustTunnel не найден"
                  }
                  val config = SecureProfileStore.load(this, SecureProfileStore.TRUST_TUNNEL)
                  val permissionIntent: Intent? = VpnService.prepare(this)
                  if (permissionIntent == null) {
                    TrustTunnelManager.start(this, config)
                  } else {
                    pendingTrustConfig = config
                    vpnPermission.launch(permissionIntent)
                  }
                } else {
                  if (trustTunnelConnectionState != ConnectionState.Disconnected) {
                    TrustTunnelManager.stop(this)
                  }
                  check(SecureProfileStore.exists(this, SecureProfileStore.SING_BOX)) {
                    "Основной профиль не найден"
                  }
                  val permissionIntent: Intent? = VpnService.prepare(this)
                  if (permissionIntent == null) {
                    VeilarkVpnService.start(this)
                  } else {
                    pendingConfigPath = SecureProfileStore.SING_BOX
                    vpnPermission.launch(permissionIntent)
                  }
                }
              }
            }.onFailure {
              importError = it.message ?: "Не удалось запустить VPN"
              TechnicalLogStore.error("APP", "Команда подключения не выполнена")
            }
          },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    consumeTileConnectIntent(intent)
  }

  private fun consumeTileConnectIntent(intent: Intent?) {
    if (intent?.action != ACTION_CONNECT_FROM_TILE) return
    intent.action = null
    tileConnectRequests.value += 1
  }

  private fun shouldRequestNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
      PackageManager.PERMISSION_GRANTED &&
      !getSharedPreferences("app_permissions", MODE_PRIVATE)
        .getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)

  private fun markNotificationPermissionRequested() {
    getSharedPreferences("app_permissions", MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
      .apply()
  }

  /**
   * A catalog can survive while a previously selected entry is removed or a legacy migration
   * changes its id. Reconcile the persisted active profile before Compose derives its initial
   * state, so the UI and VPN service cannot point at different sources after process restart.
   */
  private fun reconcileStartupProfiles(preferences: SharedPreferences) {
    runCatching {
      val singProfiles = SingBoxCatalog.load(this)
      val trustProfiles = TrustTunnelCatalog.load(this)
      val savedSingId = preferences.getString("selected_sing_profile", null)
      val savedTrustId = preferences.getString("selected_trust_profile", null)
      val singId = SubscriptionSelectionPolicy.resolve(
        savedSingId,
        singProfiles.map { it.id },
      )
      val trustId = SubscriptionSelectionPolicy.resolve(
        savedTrustId,
        trustProfiles.map { it.id },
      )
      val editor = preferences.edit()
      singProfiles.firstOrNull { it.id == singId }?.let { active ->
        if (savedSingId != active.id || !SecureProfileStore.exists(this, SecureProfileStore.SING_BOX)) {
          SingBoxCatalog.activate(this, active)
        }
        editor
          .putString("selected_sing_profile", active.id)
          .putString("sing_display_name", active.name)
          .putString("nodes", ProfileSelection.encodeNodes(active.nodes))
          .putString("selected_node", active.selectedNodeTag)
      }
      trustProfiles.firstOrNull { it.id == trustId }?.let { active ->
        if (savedTrustId != active.id || !SecureProfileStore.exists(this, SecureProfileStore.TRUST_TUNNEL)) {
          TrustTunnelCatalog.activate(this, trustProfiles, active.id)
        }
        editor
          .putString("selected_trust_profile", active.id)
          .putString("trust_display_name", active.name)
      }
      val requestedEngine = preferences.getString("profile_engine", ProfileEngine.SING_BOX)
      val resolvedEngine = when {
        requestedEngine == ProfileEngine.TRUST_TUNNEL && trustId != null -> ProfileEngine.TRUST_TUNNEL
        requestedEngine == ProfileEngine.SING_BOX && singId != null -> ProfileEngine.SING_BOX
        singId != null -> ProfileEngine.SING_BOX
        trustId != null -> ProfileEngine.TRUST_TUNNEL
        else -> requestedEngine ?: ProfileEngine.SING_BOX
      }
      val displayName = if (resolvedEngine == ProfileEngine.TRUST_TUNNEL) {
        trustProfiles.firstOrNull { it.id == trustId }?.name
      } else {
        singProfiles.firstOrNull { it.id == singId }?.name
      }
      editor
        .putString("profile_engine", resolvedEngine)
        .apply {
          if (displayName != null) putString("display_name", displayName)
        }
        .apply()
    }.onFailure {
      TechnicalLogStore.warning("PROFILE", "Не удалось сверить сохранённый выбор профиля")
    }
  }

  private fun migrateAutomaticRouting() {
    val preferences = getSharedPreferences("profile_meta", MODE_PRIVATE)
    if (preferences.getString("routing_mode", null) != ProfileSelection.ROUTING_RU_DIRECT) {
      return
    }
    runCatching {
      if (SecureProfileStore.exists(this, SecureProfileStore.SING_BOX)) {
        val migrated = ProfileSelection.applyRouting(
          SecureProfileStore.load(this, SecureProfileStore.SING_BOX),
          ProfileSelection.ROUTING_ALL,
        )
        Libbox.checkConfig(migrated)
        SecureProfileStore.save(this, SecureProfileStore.SING_BOX, migrated)
      }
    }.onSuccess {
      preferences.edit()
        .putString("routing_mode", ProfileSelection.ROUTING_ALL)
        .remove("direct_routes")
        .remove("vpn_routes")
        .apply()
    }
  }

  companion object {
    private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
    val ACTION_CONNECT_FROM_TILE: String = "${BuildConfig.APPLICATION_ID}.CONNECT_FROM_TILE"
    const val MAX_CONFIG_SIZE = 2 * 1024 * 1024
  }
}
