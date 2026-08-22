package com.example.veilark.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.veilark.theme.VeilarkTheme
import com.example.veilark.R
import com.example.veilark.diagnostics.TechnicalLogEntry
import com.example.veilark.profile.ConnectionNode
import com.example.veilark.profile.InstalledApp
import com.example.veilark.profile.InstalledAppLoader
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.profile.SingBoxCatalogEntry
import com.example.veilark.protocol.allowsProfileSwitch
import com.example.veilark.vpn.ConnectionState
import com.example.veilark.vpn.StartupStage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SubscriptionUiItem(
  val id: String,
  val name: String,
  val nodeCount: Int,
  val refreshable: Boolean,
  val deletable: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  profileName: String? = null,
  connectionState: ConnectionState = ConnectionState.Disconnected,
  startupStage: StartupStage = StartupStage.Idle,
  importError: String? = null,
  failureCode: String? = null,
  diagnosticReportAvailable: Boolean = false,
  technicalLogs: List<TechnicalLogEntry> = emptyList(),
  connectionNodes: List<ConnectionNode> = emptyList(),
  singBoxSubscriptions: List<SingBoxCatalogEntry> = emptyList(),
  trustSubscriptions: List<SubscriptionUiItem> = emptyList(),
  selectedSubscriptionId: String? = null,
  selectedTrustSubscriptionId: String? = null,
  selectedNodeTag: String = ProfileSelection.AUTOMATIC_TAG,
  nodeLatencies: Map<String, Int> = emptyMap(),
  automaticNodeTag: String? = null,
  automaticNodeSelectionAvailable: Boolean = true,
  latencyChecking: Boolean = false,
  routingMode: String = ProfileSelection.ROUTING_ALL,
  directRoutes: String = "",
  vpnRoutes: String = "",
  applicationMode: String = ProfileSelection.APPS_ALL,
  dpiMode: String = ProfileSelection.DPI_OFF,
  selectedApplications: Set<String> = emptySet(),
  installedApplications: List<InstalledApp> = emptyList(),
  routingAvailable: Boolean = true,
  trustTunnelActive: Boolean = false,
  singBoxAvailable: Boolean = true,
  trustTunnelAvailable: Boolean = false,
  engineDescription: String = "sing-box 1.13.14",
  subscriptionRefreshAvailable: Boolean = false,
  refreshingSubscription: Boolean = false,
  selfUpdateEnabled: Boolean = true,
  updateStatus: String = "Проверка обновлений…",
  updateNotes: String = "",
  updateAvailable: Boolean = false,
  updating: Boolean = false,
  updateProgress: Float? = null,
  importing: Boolean = false,
  onImportFile: () -> Unit = {},
  onImportUrl: (String) -> Unit = {},
  onScanQr: ((String) -> Unit) -> Unit = {},
  onSelectNode: (String) -> Unit = {},
  onSelectSubscription: (String) -> Unit = {},
  onRefreshSubscription: () -> Unit = {},
  onDeleteSubscription: (String) -> Unit = {},
  onSwitchProfile: () -> Unit = {},
  onRefreshLatency: () -> Unit = {},
  onOpenRouting: () -> Unit = {},
  onApplyRouting: (String, String, String, String, String, Set<String>) -> Unit =
    { _, _, _, _, _, _ -> },
  onCopyDiagnostic: () -> Unit = {},
  onClearTechnicalLogs: () -> Unit = {},
  onRunDiagnostics: () -> Unit = {},
  onCheckUpdate: () -> Unit = {},
  onUpdate: () -> Unit = {},
  onConnect: () -> Unit = {},
) {
  var showImport by remember { mutableStateOf(false) }
  var showNodes by remember { mutableStateOf(false) }
  var showRouting by remember { mutableStateOf(false) }
  var showTechnicalLogs by remember { mutableStateOf(false) }
  var subscriptionUrl by remember { mutableStateOf("") }
  var importSubmitted by remember { mutableStateOf(false) }
  var observedImporting by remember { mutableStateOf(false) }
  var importAttempted by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  val activeSubscriptions = remember(
    trustTunnelActive,
    trustSubscriptions,
    singBoxSubscriptions,
  ) {
    if (trustTunnelActive) {
      trustSubscriptions
    } else {
      singBoxSubscriptions.map { subscription ->
        SubscriptionUiItem(
          id = subscription.id,
          name = subscription.name,
          nodeCount = subscription.nodes.size,
          refreshable = subscription.sourceUrl != null,
          deletable = true,
        )
      }
    }
  }

  LaunchedEffect(importing, importError) {
    if (importSubmitted && importing) observedImporting = true
    if (importSubmitted && observedImporting && !importing) {
      if (importError == null) {
        showImport = false
        importAttempted = false
      }
      importSubmitted = false
      observedImporting = false
    }
  }

  if (showTechnicalLogs) {
    TechnicalLogScreen(
      entries = technicalLogs,
      onBack = { showTechnicalLogs = false },
      onClear = onClearTechnicalLogs,
      onRunDiagnostics = onRunDiagnostics,
    )
    return
  }

  if (showImport) {
    ImportDialog(
      subscriptionUrl = subscriptionUrl,
      importing = importing,
      error = importError.takeIf { importAttempted },
      onUrlChange = {
        subscriptionUrl = it
        importAttempted = false
      },
      onPaste = {
        clipboard.getText()?.text?.trim()?.takeIf(String::isNotEmpty)?.let {
          subscriptionUrl = it
          importAttempted = false
        }
      },
      onDismiss = {
        showImport = false
        importAttempted = false
      },
      onImportFile = {
        showImport = false
        onImportFile()
      },
      onScanQr = {
        onScanQr { scanned ->
          val value = scanned.trim()
          subscriptionUrl = value
          importAttempted = true
          importSubmitted = true
          onImportUrl(value)
        }
      },
      onImportUrl = {
        importAttempted = true
        importSubmitted = true
        onImportUrl(subscriptionUrl.trim())
      },
    )
  }
  if (showRouting) {
    RoutingSettingsDialog(
      routingMode = routingMode,
      directRoutes = directRoutes,
      vpnRoutes = vpnRoutes,
      applicationMode = applicationMode,
      dpiMode = dpiMode,
      selectedApplications = selectedApplications,
      installedApplications = installedApplications,
      trustTunnelActive = trustTunnelActive,
      onApply = { route, direct, vpn, apps, dpi, packages ->
        onApplyRouting(route, direct, vpn, apps, dpi, packages)
        showRouting = false
      },
      onDismiss = { showRouting = false },
    )
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "Veilark",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
          )
        },
        actions = {
          CompactIconAction(
            glyph = ActionGlyph.Journal,
            description = "Открыть технический журнал",
            onClick = { showTechnicalLogs = true },
          )
          CompactIconAction(
            glyph = ActionGlyph.Add,
            description = "Добавить подписку или профиль",
            enabled = !importing,
            onClick = { showImport = true },
          )
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
        ),
      )
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxHeight()
        .fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = 720.dp),
      contentPadding = PaddingValues(
        start = 16.dp,
        top = innerPadding.calculateTopPadding() + 12.dp,
        end = 16.dp,
        bottom = 32.dp,
      ),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        ConnectionCard(
          profileName = profileName,
          state = connectionState,
          stage = startupStage,
          latency = nodeLatencies[
            if (selectedNodeTag == ProfileSelection.AUTOMATIC_TAG) {
              automaticNodeTag
            } else {
              selectedNodeTag
            }
          ],
          importing = importing,
          onConnect = if (profileName == null) ({ showImport = true }) else onConnect,
        )
      }
      item {
        EngineSelectorCard(
          trustTunnelActive = trustTunnelActive,
          singBoxAvailable = singBoxAvailable,
          trustTunnelAvailable = trustTunnelAvailable,
          connectionState = connectionState,
          onSwitchProfile = onSwitchProfile,
        )
      }
      if (importError != null) {
        item {
          ErrorCard(
            message = importError,
            code = failureCode,
            diagnosticReportAvailable = diagnosticReportAvailable,
            onCopyDiagnostic = onCopyDiagnostic,
          )
        }
      }
      item {
        ProfileCard(
          profileName = profileName,
          trustTunnelActive = trustTunnelActive,
          nodes = connectionNodes,
          subscriptions = activeSubscriptions,
          selectedSubscriptionId = if (trustTunnelActive) {
            selectedTrustSubscriptionId
          } else {
            selectedSubscriptionId
          },
          selectedTag = selectedNodeTag,
          subscriptionRefreshAvailable = subscriptionRefreshAvailable,
          refreshingSubscription = refreshingSubscription,
          expanded = showNodes,
          latencies = nodeLatencies,
          automaticAvailable = automaticNodeSelectionAvailable,
          canRefreshLatency = trustTunnelActive ||
            profileName != null,
          latencyChecking = latencyChecking,
          onRefreshSubscription = onRefreshSubscription,
          onAddSubscription = {
            subscriptionUrl = ""
            importAttempted = false
            showImport = true
          },
          onPasteSubscription = {
            subscriptionUrl = clipboard.getText()?.text?.trim().orEmpty()
            importAttempted = false
            showImport = true
          },
          onScanSubscriptionQr = {
            onScanQr { scanned ->
              val value = scanned.trim()
              subscriptionUrl = value
              importAttempted = true
              importSubmitted = true
              onImportUrl(value)
            }
          },
          onDeleteSubscription = onDeleteSubscription,
          onRefreshLatency = onRefreshLatency,
          onSelectNode = {
            onSelectNode(it)
            showNodes = false
          },
          onSelectSubscription = {
            onSelectSubscription(it)
          },
          onToggleNodes = {
            if (connectionNodes.isEmpty() && activeSubscriptions.isEmpty()) {
              showImport = true
            } else {
              showNodes = !showNodes
            }
          },
        )
      }
      item {
        RoutingCard(
          routingMode = routingMode,
          applicationMode = applicationMode,
          dpiMode = dpiMode,
          selectedApplicationCount = selectedApplications.size,
          available = routingAvailable,
          onClick = {
            if (routingAvailable) {
              onOpenRouting()
              showRouting = true
            }
          },
        )
      }
      if (selfUpdateEnabled) {
        item {
          UpdateCard(
            status = updateStatus,
            notes = updateNotes,
            available = updateAvailable,
            updating = updating,
            progress = updateProgress,
            onCheck = onCheckUpdate,
            onUpdate = onUpdate,
          )
        }
      }
      item {
        Text(
          text = "Сетевое ядро $engineDescription · журнал не содержит ключей доступа",
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

@Composable
private fun UpdateCard(
  status: String,
  notes: String,
  available: Boolean,
  updating: Boolean,
  progress: Float?,
  onCheck: () -> Unit,
  onUpdate: () -> Unit,
) {
  var showConfirmation by remember { mutableStateOf(false) }
  val normalizedProgress = progress?.coerceIn(0f, 1f)
  if (showConfirmation && available && !updating) {
    AlertDialog(
      onDismissRequest = { showConfirmation = false },
      title = { Text("Обновить Veilark?") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(status, style = MaterialTheme.typography.titleSmall)
          if (notes.isNotBlank()) {
            Text(
              text = notes,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Text(
              text = "Описание изменений для этой версии не указано.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Text(
            text = "Перед установкой Veilark проверит подпись манифеста, SHA-256 и подпись APK. Установку подтвердит Android.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showConfirmation = false
            onUpdate()
          },
        ) {
          Icon(Icons.Rounded.Download, contentDescription = null)
          Text("Обновить", modifier = Modifier.padding(start = 8.dp))
        }
      },
      dismissButton = {
        CompactIconAction(
          glyph = ActionGlyph.Close,
          description = "Отмена",
          onClick = { showConfirmation = false },
        )
      },
    )
  }
  Column {
    Text(
      text = "Обновления",
      modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Veilark", fontWeight = FontWeight.Medium)
            Text(
              status,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (available && notes.isNotBlank() && !updating) {
              Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
          CompactIconAction(
            glyph = if (available) ActionGlyph.Download else ActionGlyph.Refresh,
            description = when {
              updating -> "Обновление загружается"
              available -> "Показать изменения и загрузить обновление"
              else -> "Проверить обновление приложения"
            },
            onClick = if (available) ({ showConfirmation = true }) else onCheck,
            enabled = !updating,
            loading = updating && normalizedProgress == null,
          )
        }
        if (updating && normalizedProgress != null) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            LinearProgressIndicator(
              progress = { normalizedProgress },
              modifier = Modifier.weight(1f),
            )
            Text(
              text = "${(normalizedProgress * 100).toInt()}%",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ConnectionCard(
  profileName: String?,
  state: ConnectionState,
  stage: StartupStage,
  latency: Int?,
  importing: Boolean,
  onConnect: () -> Unit,
) {
  val connected = state == ConnectionState.Connected
  val connecting = state == ConnectionState.Connecting
  val progress by animateFloatAsState(
    targetValue = if (connected) 1f else 0f,
    label = "connection progress",
  )
  val container = when {
    connected -> MaterialTheme.colorScheme.primaryContainer
    state == ConnectionState.Failed -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
  }
  Surface(
    modifier = Modifier.fillMaxWidth().animateContentSize(),
    shape = RoundedCornerShape(32.dp),
    color = container,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Surface(
        modifier = Modifier
          .size(104.dp),
        shape = CircleShape,
        color = if (connected) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.surfaceContainerLowest
        },
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (connecting) {
            CircularProgressIndicator(
              modifier = Modifier.size(62.dp),
              strokeWidth = 4.dp,
            )
          } else {
            ShieldMark(
              checked = connected,
              progress = progress,
              color = if (connected) {
                MaterialTheme.colorScheme.onPrimary
              } else {
                MaterialTheme.colorScheme.primary
              },
              modifier = Modifier.size(58.dp),
            )
          }
        }
      }
      Spacer(Modifier.height(22.dp))
      AnimatedContent(
        targetState = state,
        transitionSpec = {
          (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith
            (fadeOut(tween(160)) + slideOutVertically { -it / 3 })
        },
        label = "connection state",
      ) { current ->
        Text(
          text = when (current) {
            ConnectionState.Disconnected -> "VPN выключен"
            ConnectionState.Connecting -> stage.safeTitle
            ConnectionState.Connected -> "Соединение защищено"
            ConnectionState.Failed -> "Не удалось подключиться"
          },
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          textAlign = TextAlign.Center,
        )
      }
      Spacer(Modifier.height(6.dp))
      Text(
        text = when {
          profileName == null -> "Добавьте подписку или профиль"
          connecting -> "Это может занять несколько секунд"
          connected -> profileName
          else -> "Готово к безопасному подключению"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      if (connected && latency != null) {
        Spacer(Modifier.height(8.dp))
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        ) {
          Text(
            text = "$latency мс",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
      Spacer(Modifier.height(24.dp))
      Button(
        onClick = onConnect,
        enabled = !importing,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = if (connected || connecting) {
          ButtonDefaults.filledTonalButtonColors()
        } else {
          ButtonDefaults.buttonColors()
        },
      ) {
        Text(
          text = when {
            importing -> "Проверяем профиль…"
            profileName == null -> "Добавить профиль"
            connecting -> "Отменить"
            connected -> "Отключить"
            else -> "Подключить"
          },
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
  }
}

@Composable
private fun EngineSelectorCard(
  trustTunnelActive: Boolean,
  singBoxAvailable: Boolean,
  trustTunnelAvailable: Boolean,
  connectionState: ConnectionState,
  onSwitchProfile: () -> Unit,
) {
  val alternativeAvailable = if (trustTunnelActive) singBoxAvailable else trustTunnelAvailable
  val idle = connectionState.allowsProfileSwitch()
  val canSwitch = idle && alternativeAvailable
  val stackModes = LocalDensity.current.fontScale >= 1.3f
  Column {
    Text(
      text = "Режим подключения",
      modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
      modifier = Modifier.fillMaxWidth().animateContentSize(),
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (stackModes) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EngineModeButton(
              title = "sing-box",
              trust = false,
              selected = !trustTunnelActive,
              enabled = idle && singBoxAvailable,
              modifier = Modifier.fillMaxWidth(),
              onClick = { if (trustTunnelActive && canSwitch) onSwitchProfile() },
            )
            EngineModeButton(
              title = "TrustTunnel",
              trust = true,
              selected = trustTunnelActive,
              enabled = idle && trustTunnelAvailable,
              modifier = Modifier.fillMaxWidth(),
              onClick = { if (!trustTunnelActive && canSwitch) onSwitchProfile() },
            )
          }
        } else {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            EngineModeButton(
              title = "sing-box",
              trust = false,
              selected = !trustTunnelActive,
              enabled = idle && singBoxAvailable,
              modifier = Modifier.weight(1f),
              onClick = { if (trustTunnelActive && canSwitch) onSwitchProfile() },
            )
            EngineModeButton(
              title = "TrustTunnel",
              trust = true,
              selected = trustTunnelActive,
              enabled = idle && trustTunnelAvailable,
              modifier = Modifier.weight(1f),
              onClick = { if (!trustTunnelActive && canSwitch) onSwitchProfile() },
            )
          }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            when {
              !idle ->
                "Сначала отключите VPN, чтобы сменить режим"
              !alternativeAvailable ->
                "Другой режим станет доступен после добавления профиля"
              trustTunnelActive ->
                "H2/H3 · Anti-DPI · встроенные Frankfurt и Netherlands"
              else ->
                "VLESS · Trojan · Hysteria · ручная маршрутизация"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun EngineModeButton(
  title: String,
  trust: Boolean,
  selected: Boolean,
  enabled: Boolean,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  val container = if (selected) {
    MaterialTheme.colorScheme.primaryContainer
  } else {
    MaterialTheme.colorScheme.surfaceContainerHigh
  }
  val content = if (selected) {
    MaterialTheme.colorScheme.onPrimaryContainer
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(
    modifier = modifier
      .heightIn(min = 58.dp)
      .selectable(
        selected = selected,
        enabled = enabled,
        role = Role.RadioButton,
        onClick = { if (!selected) onClick() },
      ),
    shape = RoundedCornerShape(18.dp),
    color = container,
    contentColor = content,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      EngineGlyph(trust = trust, color = content, modifier = Modifier.size(22.dp))
      Text(
        title,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TechnicalLogScreen(
  entries: List<TechnicalLogEntry>,
  onBack: () -> Unit,
  onClear: () -> Unit,
  onRunDiagnostics: () -> Unit,
) {
  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = { Text("Технический журнал") },
        navigationIcon = {
          CompactIconAction(
            glyph = ActionGlyph.Back,
            description = "Назад",
            onClick = onBack,
          )
        },
        actions = {
          CompactIconAction(
            glyph = ActionGlyph.Diagnostics,
            description = "Проверить внешние сервисы",
            onClick = onRunDiagnostics,
          )
          CompactIconAction(
            glyph = ActionGlyph.Clear,
            description = "Очистить журнал",
            enabled = entries.isNotEmpty(),
            onClick = onClear,
          )
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
        ),
      )
    },
  ) { innerPadding ->
    if (entries.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "Ошибок и сетевых событий пока нет",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
          start = 16.dp,
          top = innerPadding.calculateTopPadding() + 8.dp,
          end = 16.dp,
          bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(entries.asReversed(), key = { "${it.timestamp}-${it.component}-${it.message}" }) {
          LogEntryCard(it)
        }
      }
    }
  }
}

@Composable
private fun LogEntryCard(entry: TechnicalLogEntry) {
  val formatter = remember {
    DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault())
  }
  val accent = when (entry.level) {
    "ERROR" -> MaterialTheme.colorScheme.error
    "WARN" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
  }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(Modifier.size(8.dp).background(accent, CircleShape))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(entry.component, style = MaterialTheme.typography.labelLarge, color = accent)
          Text(
            formatter.format(Instant.ofEpochMilli(entry.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(entry.message, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun ProfileCard(
  profileName: String?,
  trustTunnelActive: Boolean,
  nodes: List<ConnectionNode>,
  subscriptions: List<SubscriptionUiItem>,
  selectedSubscriptionId: String?,
  selectedTag: String,
  subscriptionRefreshAvailable: Boolean,
  refreshingSubscription: Boolean,
  expanded: Boolean,
  latencies: Map<String, Int>,
  automaticAvailable: Boolean,
  canRefreshLatency: Boolean,
  latencyChecking: Boolean,
  onRefreshSubscription: () -> Unit,
  onAddSubscription: () -> Unit,
  onPasteSubscription: () -> Unit,
  onScanSubscriptionQr: () -> Unit,
  onDeleteSubscription: (String) -> Unit,
  onRefreshLatency: () -> Unit,
  onSelectNode: (String) -> Unit,
  onSelectSubscription: (String) -> Unit,
  onToggleNodes: () -> Unit,
) {
  val selectedNode = nodes.firstOrNull { it.tag == selectedTag }
  var pendingDeletionId by remember { mutableStateOf<String?>(null) }
  val pendingDeletion = subscriptions.firstOrNull { it.id == pendingDeletionId }

  if (pendingDeletion != null) {
    AlertDialog(
      onDismissRequest = { pendingDeletionId = null },
      icon = {
        Icon(
          imageVector = Icons.Rounded.DeleteOutline,
          contentDescription = null,
        )
      },
      title = { Text(stringResource(R.string.subscription_delete_title)) },
      text = {
        Text(
          stringResource(R.string.subscription_delete_message, pendingDeletion.name),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            val id = pendingDeletion.id
            pendingDeletionId = null
            onDeleteSubscription(id)
          },
        ) {
          Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeletionId = null }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }
  Column {
    Text(
      text = if (trustTunnelActive) "Профиль TrustTunnel" else "Профиль sing-box",
      modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
      modifier = Modifier.fillMaxWidth().animateContentSize(),
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
      Column {
        ListItem(
          modifier = Modifier
            .clickable(onClick = onToggleNodes)
            .semantics {
              stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
            },
          headlineContent = {
            Text(
              profileName ?: "Профиль не добавлен",
              fontWeight = FontWeight.Medium,
            )
          },
          supportingContent = {
            Text(
              when {
                profileName == null -> "Импортировать подписку"
                selectedNode != null && !trustTunnelActive ->
                  "${selectedNode.name} · ${selectedNode.protocol}"
                selectedNode != null -> selectedNode.protocol
                nodes.isNotEmpty() -> "Автоматический выбор · ${nodes.size} узлов"
                else -> "Импортированная конфигурация"
              },
            )
          },
          leadingContent = {
            Surface(
              modifier = Modifier.size(44.dp),
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
              Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                  targetState = trustTunnelActive,
                  transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                  label = "profile engine mark",
                ) { trust ->
                  EngineGlyph(
                    trust = trust,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(25.dp),
                  )
                }
              }
            }
          },
          trailingContent = {
            DropdownChevron(
              expanded = expanded,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
      }
    }

    if (expanded) {
      ConnectionPickerSheet(
        trustTunnelActive = trustTunnelActive,
        subscriptions = subscriptions,
        selectedSubscriptionId = selectedSubscriptionId,
        nodes = nodes,
        selectedTag = selectedTag,
        latencies = latencies,
        automaticAvailable = automaticAvailable,
        subscriptionRefreshAvailable = subscriptionRefreshAvailable,
        refreshingSubscription = refreshingSubscription,
        canRefreshLatency = canRefreshLatency,
        latencyChecking = latencyChecking,
        onDismiss = onToggleNodes,
        onRefreshSubscription = onRefreshSubscription,
        onAddSubscription = onAddSubscription,
        onPasteSubscription = onPasteSubscription,
        onScanSubscriptionQr = onScanSubscriptionQr,
        onRefreshLatency = onRefreshLatency,
        onSelectSubscription = onSelectSubscription,
        onSelectNode = onSelectNode,
        onRequestDelete = { pendingDeletionId = it },
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPickerSheet(
  trustTunnelActive: Boolean,
  subscriptions: List<SubscriptionUiItem>,
  selectedSubscriptionId: String?,
  nodes: List<ConnectionNode>,
  selectedTag: String,
  latencies: Map<String, Int>,
  automaticAvailable: Boolean,
  subscriptionRefreshAvailable: Boolean,
  refreshingSubscription: Boolean,
  canRefreshLatency: Boolean,
  latencyChecking: Boolean,
  onDismiss: () -> Unit,
  onRefreshSubscription: () -> Unit,
  onAddSubscription: () -> Unit,
  onPasteSubscription: () -> Unit,
  onScanSubscriptionQr: () -> Unit,
  onRefreshLatency: () -> Unit,
  onSelectSubscription: (String) -> Unit,
  onSelectNode: (String) -> Unit,
  onRequestDelete: (String) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.82f),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = stringResource(R.string.connection_picker_title),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = stringResource(
            if (trustTunnelActive) R.string.profiles_and_servers
            else R.string.subscriptions_and_servers,
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        CompactIconAction(
          glyph = ActionGlyph.Add,
          description = stringResource(R.string.subscription_add),
          onClick = onAddSubscription,
        )
        CompactIconAction(
          glyph = ActionGlyph.Paste,
          description = stringResource(R.string.subscription_paste),
          onClick = onPasteSubscription,
        )
        CompactIconAction(
          glyph = ActionGlyph.Qr,
          description = stringResource(R.string.subscription_scan_qr),
          onClick = onScanSubscriptionQr,
        )
        CompactIconAction(
          glyph = ActionGlyph.Refresh,
          description = when {
            refreshingSubscription -> stringResource(R.string.subscription_refreshing)
            subscriptionRefreshAvailable -> stringResource(R.string.subscription_refresh)
            else -> stringResource(R.string.subscription_refresh_unavailable)
          },
          enabled = subscriptionRefreshAvailable && !refreshingSubscription,
          loading = refreshingSubscription,
          onClick = onRefreshSubscription,
        )
        CompactIconAction(
          glyph = ActionGlyph.Ping,
          description = stringResource(
            if (latencyChecking) R.string.latency_checking else R.string.latency_check,
          ),
          enabled = canRefreshLatency && !latencyChecking,
          loading = latencyChecking,
          onClick = onRefreshLatency,
        )
      }

      HorizontalDivider()

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (subscriptions.isNotEmpty()) {
          item(key = "subscriptions-label") {
            PickerSectionLabel(stringResource(R.string.subscriptions))
          }
          items(subscriptions, key = { "subscription-${it.id}" }) { subscription ->
            SubscriptionChoice(
              name = subscription.name,
              nodeCount = subscription.nodeCount,
              refreshable = subscription.refreshable,
              selected = selectedSubscriptionId == subscription.id,
              onClick = { onSelectSubscription(subscription.id) },
              onDelete = if (subscription.deletable) {
                { onRequestDelete(subscription.id) }
              } else {
                null
              },
            )
          }
        }

        if (automaticAvailable || nodes.isNotEmpty()) {
          item(key = "servers-label") {
            PickerSectionLabel(stringResource(R.string.servers))
          }
        }
        if (automaticAvailable) {
          item(key = "automatic-node") {
            NodeChoice(
              title = stringResource(R.string.automatic),
              subtitle = stringResource(R.string.automatic_node_description),
              selected = selectedTag == ProfileSelection.AUTOMATIC_TAG,
              onClick = { onSelectNode(ProfileSelection.AUTOMATIC_TAG) },
            )
          }
        }
        items(nodes, key = { "node-${it.tag}" }) { node ->
          NodeChoice(
            title = node.name,
            subtitle = buildString {
              append(node.protocol)
              latencies[node.tag]?.let { append(" · $it мс") }
            },
            selected = selectedTag == node.tag,
            onClick = { onSelectNode(node.tag) },
          )
        }

        if (subscriptions.isEmpty() && nodes.isEmpty()) {
          item(key = "empty") {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 40.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = stringResource(R.string.profiles_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
              )
              Text(
                text = stringResource(R.string.profiles_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PickerSectionLabel(text: String) {
  Text(
    text = text,
    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
  )
}

private enum class ActionGlyph {
  Add,
  Back,
  Check,
  Clear,
  Close,
  Copy,
  Download,
  Diagnostics,
  File,
  Import,
  Journal,
  Paste,
  Ping,
  Qr,
  Refresh,
}

@Composable
private fun CompactIconAction(
  glyph: ActionGlyph,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  loading: Boolean = false,
) {
  val color = if (enabled) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
  }
  IconButton(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.semantics { contentDescription = description },
  ) {
    if (loading) {
      CircularProgressIndicator(
        modifier = Modifier.size(21.dp),
        strokeWidth = 2.dp,
        color = color,
      )
    } else {
      Icon(
        imageVector = glyph.imageVector(),
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(24.dp),
      )
    }
  }
}

private fun ActionGlyph.imageVector(): ImageVector = when (this) {
  ActionGlyph.Add -> Icons.Rounded.Add
  ActionGlyph.Back -> Icons.AutoMirrored.Rounded.ArrowBack
  ActionGlyph.Check -> Icons.Rounded.Check
  ActionGlyph.Clear -> Icons.Rounded.DeleteOutline
  ActionGlyph.Close -> Icons.Rounded.Close
  ActionGlyph.Copy -> Icons.Rounded.ContentCopy
  ActionGlyph.Download -> Icons.Rounded.Download
  ActionGlyph.Diagnostics -> Icons.Rounded.Troubleshoot
  ActionGlyph.File -> Icons.Rounded.Description
  ActionGlyph.Import -> Icons.Rounded.FileDownload
  ActionGlyph.Journal -> Icons.AutoMirrored.Rounded.Article
  ActionGlyph.Paste -> Icons.Rounded.ContentPaste
  ActionGlyph.Ping -> Icons.Rounded.Speed
  ActionGlyph.Qr -> Icons.Rounded.QrCodeScanner
  ActionGlyph.Refresh -> Icons.Rounded.Refresh
}

@Composable
private fun EngineGlyph(
  trust: Boolean,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier) {
    val stroke = Stroke(width = size.minDimension * 0.09f, cap = StrokeCap.Round)
    if (trust) {
      val left = Path().apply {
        moveTo(size.width * 0.46f, size.height * 0.28f)
        cubicTo(
          size.width * 0.20f, size.height * 0.20f,
          size.width * 0.12f, size.height * 0.48f,
          size.width * 0.30f, size.height * 0.62f,
        )
        cubicTo(
          size.width * 0.40f, size.height * 0.70f,
          size.width * 0.51f, size.height * 0.61f,
          size.width * 0.57f, size.height * 0.54f,
        )
      }
      val right = Path().apply {
        moveTo(size.width * 0.54f, size.height * 0.72f)
        cubicTo(
          size.width * 0.80f, size.height * 0.80f,
          size.width * 0.88f, size.height * 0.52f,
          size.width * 0.70f, size.height * 0.38f,
        )
        cubicTo(
          size.width * 0.60f, size.height * 0.30f,
          size.width * 0.49f, size.height * 0.39f,
          size.width * 0.43f, size.height * 0.46f,
        )
      }
      drawPath(left, color, style = stroke)
      drawPath(right, color, style = stroke)
    } else {
      val shield = Path().apply {
        moveTo(size.width * 0.50f, size.height * 0.10f)
        lineTo(size.width * 0.82f, size.height * 0.24f)
        lineTo(size.width * 0.78f, size.height * 0.61f)
        cubicTo(
          size.width * 0.75f, size.height * 0.78f,
          size.width * 0.61f, size.height * 0.88f,
          size.width * 0.50f, size.height * 0.93f,
        )
        cubicTo(
          size.width * 0.39f, size.height * 0.88f,
          size.width * 0.25f, size.height * 0.78f,
          size.width * 0.22f, size.height * 0.61f,
        )
        lineTo(size.width * 0.18f, size.height * 0.24f)
        close()
      }
      val check = Path().apply {
        moveTo(size.width * 0.34f, size.height * 0.52f)
        lineTo(size.width * 0.46f, size.height * 0.64f)
        lineTo(size.width * 0.68f, size.height * 0.39f)
      }
      drawPath(shield, color, style = stroke)
      drawPath(check, color, style = stroke)
    }
  }
}

@Composable
private fun DropdownChevron(
  expanded: Boolean,
  color: Color,
) {
  val rotation by animateFloatAsState(
    targetValue = if (expanded) 180f else 0f,
    animationSpec = tween(180),
    label = "node dropdown chevron",
  )
  Canvas(
    Modifier
      .size(24.dp)
      .graphicsLayer { rotationZ = rotation },
  ) {
    val path = Path().apply {
      moveTo(size.width * 0.25f, size.height * 0.40f)
      lineTo(size.width * 0.50f, size.height * 0.65f)
      lineTo(size.width * 0.75f, size.height * 0.40f)
    }
    drawPath(
      path,
      color,
      style = Stroke(width = size.minDimension * 0.09f, cap = StrokeCap.Round),
    )
  }
}

@Composable
private fun RoutingCard(
  routingMode: String,
  applicationMode: String,
  dpiMode: String,
  selectedApplicationCount: Int,
  available: Boolean,
  onClick: () -> Unit,
) {
  Column {
    Text(
      text = "Маршрутизация",
      modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
      modifier = Modifier.fillMaxWidth().clickable(enabled = available, onClick = onClick),
      shape = RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
      Column {
        if (available) {
          InfoRow(
            "Трафик",
            if (routingMode == ProfileSelection.ROUTING_MANUAL) {
              "Свои правила"
            } else {
              "Весь трафик через VPN"
            },
          )
          HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
          InfoRow(
            "Приложения",
            when (applicationMode) {
              ProfileSelection.APPS_ONLY -> "Только выбранные · $selectedApplicationCount"
              ProfileSelection.APPS_BYPASS -> "Исключения · $selectedApplicationCount"
              else -> "Все приложения"
            },
          )
          HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
          InfoRow(
            "Защита от DPI",
            if (dpiMode == ProfileSelection.DPI_TLS_FRAGMENT) {
              "Фрагментация TLS"
            } else {
              "Стандартная"
            },
          )
          HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
          InfoRow("DNS", "Защищённый")
          HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
          InfoRow("Защита при обрыве", "Включена")
        } else {
          InfoRow("Трафик", "Полный туннель через TrustTunnel")
          HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
          InfoRow("Защита", "H2/H3 · Anti-DPI · Kill switch")
        }
      }
    }
  }
}

@Composable
private fun SubscriptionChoice(
  name: String,
  nodeCount: Int,
  refreshable: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
  onDelete: (() -> Unit)?,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .selectable(
        selected = selected,
        role = Role.RadioButton,
        onClick = onClick,
      ),
    shape = RoundedCornerShape(16.dp),
    color = if (selected) {
      MaterialTheme.colorScheme.secondaryContainer
    } else {
      Color.Transparent
    },
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.size(38.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
          MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
          MaterialTheme.colorScheme.surfaceContainerHighest
        },
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          )
        }
      }
      Column(Modifier.weight(1f)) {
        Text(
          text = name,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${pluralStringResource(R.plurals.server_count, nodeCount, nodeCount)} · ${stringResource(if (refreshable) R.string.subscription_source_remote else R.string.subscription_source_local)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (selected) {
        Icon(
          imageVector = Icons.Rounded.Check,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(21.dp),
        )
      }
      if (onDelete != null) {
        CompactIconAction(
          glyph = ActionGlyph.Clear,
          description = stringResource(R.string.subscription_delete, name),
          onClick = onDelete,
        )
      }
    }
  }
}

@Composable
private fun NodeChoice(
  title: String,
  subtitle: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = RoundedCornerShape(18.dp),
    color = if (selected) {
      MaterialTheme.colorScheme.secondaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainerHigh
    },
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(20.dp)
          .background(
            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            CircleShape,
          ),
        contentAlignment = Alignment.Center,
      ) {
        if (selected) {
          Box(
            Modifier.size(7.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape),
          )
        }
      }
      Column {
        Text(title, fontWeight = FontWeight.Medium)
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun RoutingSettingsDialog(
  routingMode: String,
  directRoutes: String,
  vpnRoutes: String,
  applicationMode: String,
  dpiMode: String,
  selectedApplications: Set<String>,
  installedApplications: List<InstalledApp>,
  trustTunnelActive: Boolean,
  onApply: (String, String, String, String, String, Set<String>) -> Unit,
  onDismiss: () -> Unit,
) {
  var route by remember(routingMode) { mutableStateOf(routingMode) }
  var direct by remember(directRoutes) { mutableStateOf(directRoutes) }
  var vpn by remember(vpnRoutes) { mutableStateOf(vpnRoutes) }
  var appMode by remember(applicationMode) { mutableStateOf(applicationMode) }
  var dpi by remember(dpiMode) { mutableStateOf(dpiMode) }
  var packages by remember(selectedApplications) { mutableStateOf(selectedApplications) }
  var search by remember { mutableStateOf("") }
  val visibleApps = remember(installedApplications, search) {
    installedApplications
      .filter {
        search.isBlank() ||
          it.label.contains(search, ignoreCase = true) ||
          it.packageName.contains(search, ignoreCase = true)
      }
      .sortedBy { it.label.lowercase() }
  }

  val canApply =
    (appMode == ProfileSelection.APPS_ALL || packages.isNotEmpty()) &&
      (trustTunnelActive || route == ProfileSelection.ROUTING_ALL ||
        direct.isNotBlank() || vpn.isNotBlank())
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 20.dp),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth().fillMaxHeight().widthIn(max = 720.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
      ) {
        Column {
          Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                "Маршрутизация",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                if (trustTunnelActive) "TrustTunnel" else "sing-box",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            CompactIconAction(
              glyph = ActionGlyph.Close,
              description = "Закрыть настройки маршрутизации",
              onClick = onDismiss,
            )
          }
          LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (!trustTunnelActive) {
              item { SettingsSectionTitle("Трафик") }
              item {
                SettingChoice(
                  title = "Весь трафик через VPN",
                  selected = route == ProfileSelection.ROUTING_ALL,
                  onClick = { route = ProfileSelection.ROUTING_ALL },
                )
              }
              item {
                SettingChoice(
                  title = "Свои правила",
                  subtitle = "Укажите, что направлять напрямую или через VPN",
                  selected = route == ProfileSelection.ROUTING_MANUAL,
                  onClick = { route = ProfileSelection.ROUTING_MANUAL },
                )
              }
              if (route == ProfileSelection.ROUTING_MANUAL) {
                item {
                  OutlinedTextField(
                    value = direct,
                    onValueChange = { direct = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Напрямую, без VPN") },
                    supportingText = { Text("Домены и сети через пробел или с новой строки") },
                    placeholder = { Text("gosuslugi.ru\n192.168.0.0/16") },
                    minLines = 3,
                    shape = RoundedCornerShape(16.dp),
                  )
                }
                item {
                  OutlinedTextField(
                    value = vpn,
                    onValueChange = { vpn = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Всегда через VPN") },
                    supportingText = { Text("Эти правила имеют приоритет над прямыми") },
                    placeholder = { Text("youtube.com\ngooglevideo.com") },
                    minLines = 3,
                    shape = RoundedCornerShape(16.dp),
                  )
                }
              }
            } else {
              item {
                Surface(
                  shape = RoundedCornerShape(18.dp),
                  color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                  Text(
                    text = "Для TrustTunnel доступны правила по приложениям. Android применяет их при создании VPN-интерфейса, поэтому изменение вступит в силу при следующем подключении. Доменные и IP-правила доступны в sing-box.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                  )
                }
              }
            }
            item { SettingsSectionTitle("Приложения") }
            item {
              SettingChoice(
                title = "Все приложения",
                selected = appMode == ProfileSelection.APPS_ALL,
                onClick = { appMode = ProfileSelection.APPS_ALL },
              )
            }
            item {
              SettingChoice(
                title = "Только выбранные через VPN",
                selected = appMode == ProfileSelection.APPS_ONLY,
                onClick = { appMode = ProfileSelection.APPS_ONLY },
              )
            }
            item {
              SettingChoice(
                title = "Выбранные без VPN",
                selected = appMode == ProfileSelection.APPS_BYPASS,
                onClick = { appMode = ProfileSelection.APPS_BYPASS },
              )
            }
            if (appMode != ProfileSelection.APPS_ALL) {
              item {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    if (packages.isEmpty()) "Ничего не выбрано" else "Выбрано: ${packages.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  if (packages.isNotEmpty()) {
                    CompactIconAction(
                      glyph = ActionGlyph.Clear,
                      description = "Сбросить выбранные приложения",
                      onClick = { packages = emptySet() },
                    )
                  }
                }
              }
              item {
                OutlinedTextField(
                  value = search,
                  onValueChange = { search = it },
                  modifier = Modifier.fillMaxWidth(),
                  label = { Text("Найти приложение") },
                  singleLine = true,
                  shape = RoundedCornerShape(16.dp),
                )
              }
              items(visibleApps, key = InstalledApp::packageName) { app ->
                ApplicationChoice(
                  app = app,
                  selected = app.packageName in packages,
                  onToggle = {
                    packages = if (app.packageName in packages) {
                      packages - app.packageName
                    } else {
                      packages + app.packageName
                    }
                  },
                )
              }
              if (visibleApps.isEmpty()) {
                item {
                  Text(
                    if (installedApplications.isEmpty()) {
                      "Загрузка списка приложений…"
                    } else {
                      "Приложения не найдены"
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              item {
                Text(
                  "Исключённое приложение работает напрямую. Системный значок VPN при этом остаётся видимым для всего устройства.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            if (!trustTunnelActive) {
              item { SettingsSectionTitle("Защита от DPI") }
              item {
                SettingChoice(
                  title = "Стандартная",
                  subtitle = "Максимальная совместимость и скорость",
                  selected = dpi == ProfileSelection.DPI_OFF,
                  onClick = { dpi = ProfileSelection.DPI_OFF },
                )
              }
              item {
                SettingChoice(
                  title = "Фрагментация TLS",
                  subtitle = "Дробит ClientHello TCP-профилей; подключение может стать немного дольше",
                  selected = dpi == ProfileSelection.DPI_TLS_FRAGMENT,
                  onClick = { dpi = ProfileSelection.DPI_TLS_FRAGMENT },
                )
              }
            }
          }
          HorizontalDivider()
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CompactIconAction(
              glyph = ActionGlyph.Check,
              description = "Применить маршрутизацию",
              onClick = { onApply(route, direct, vpn, appMode, dpi, packages) },
              enabled = canApply,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SettingsSectionTitle(title: String) {
  Text(
    text = title,
    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun ApplicationChoice(
  app: InstalledApp,
  selected: Boolean,
  onToggle: () -> Unit,
) {
  val context = LocalContext.current
  val icon by produceState<android.graphics.Bitmap?>(null, app.packageName) {
    value = withContext(Dispatchers.IO) {
      InstalledAppLoader.loadIcon(context.applicationContext, app.packageName)
    }
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .toggleable(
        value = selected,
        role = Role.Checkbox,
        onValueChange = { onToggle() },
      )
      .padding(horizontal = 4.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Image(
        bitmap = icon!!.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(40.dp),
      )
    } else {
      Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            app.label.take(1).uppercase(),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
      }
    }
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
      Text(
        app.label,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        app.packageName,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Checkbox(checked = selected, onCheckedChange = null)
  }
}

@Composable
private fun SettingChoice(
  title: String,
  subtitle: String? = null,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    color = if (selected) {
      MaterialTheme.colorScheme.secondaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainerHigh
    },
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(18.dp)
          .background(
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
            CircleShape,
          ),
      )
      Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        subtitle?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun InfoRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 17.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      label,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyLarge,
    )
    Text(
      value,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.primary,
      textAlign = TextAlign.End,
    )
  }
}

@Composable
private fun ErrorCard(
  message: String,
  code: String?,
  diagnosticReportAvailable: Boolean,
  onCopyDiagnostic: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.errorContainer,
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = "Подключение не выполнено",
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
      if (code != null) {
        Text(
          text = "Код: $code",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.72f),
        )
      }
      if (diagnosticReportAvailable) {
        CompactIconAction(
          glyph = ActionGlyph.Copy,
          description = "Скопировать диагностику",
          onClick = onCopyDiagnostic,
          modifier = Modifier.align(Alignment.End),
        )
      }
    }
  }
}

@Composable
private fun ImportActions(
  importing: Boolean,
  onPaste: () -> Unit,
  onScanQr: () -> Unit,
  onImportFile: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
      CompactIconAction(
        glyph = ActionGlyph.Paste,
        description = "Вставить ссылку из буфера обмена",
        enabled = !importing,
        onClick = onPaste,
      )
      CompactIconAction(
        glyph = ActionGlyph.Qr,
        description = "Сканировать QR-код",
        enabled = !importing,
        onClick = onScanQr,
      )
      CompactIconAction(
        glyph = ActionGlyph.File,
        description = "Выбрать sing-box JSON-файл",
        enabled = !importing,
        onClick = onImportFile,
      )
    }
  }
}

@Composable
private fun ImportDialog(
  subscriptionUrl: String,
  importing: Boolean,
  error: String?,
  onUrlChange: (String) -> Unit,
  onPaste: () -> Unit,
  onScanQr: () -> Unit,
  onDismiss: () -> Unit,
  onImportFile: () -> Unit,
  onImportUrl: () -> Unit,
) {
  Dialog(
    onDismissRequest = { if (!importing) onDismiss() },
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).heightIn(max = 640.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
      ) {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Surface(
              modifier = Modifier.size(44.dp),
              shape = CircleShape,
              color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(10.dp),
              )
            }
            Text(
              "Добавить подписку",
              modifier = Modifier.weight(1f),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Text(
            text = "HTTPS, QR, Base64, sing-box/Xray JSON, Clash/Mihomo YAML и прямые ссылки.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
          )
          OutlinedTextField(
            value = subscriptionUrl,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ссылка подписки или профиля") },
            minLines = 1,
            maxLines = 3,
            enabled = !importing,
            isError = error != null,
            supportingText = error?.let { message ->
              {
                Text(
                  text = message,
                  color = MaterialTheme.colorScheme.error,
                )
              }
            },
            shape = RoundedCornerShape(16.dp),
          )
          ImportActions(
            importing = importing,
            onPaste = onPaste,
            onScanQr = onScanQr,
            onImportFile = onImportFile,
          )
          HorizontalDivider()
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CompactIconAction(
              glyph = ActionGlyph.Close,
              description = "Отмена",
              enabled = !importing,
              onClick = onDismiss,
            )
            CompactIconAction(
              glyph = ActionGlyph.Import,
              description = if (importing) "Профиль импортируется" else "Импортировать профиль",
              enabled = subscriptionUrl.contains("://") && !importing,
              loading = importing,
              onClick = onImportUrl,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ShieldMark(
  checked: Boolean,
  progress: Float,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier) {
    val shield = Path().apply {
      moveTo(size.width * .5f, size.height * .08f)
      lineTo(size.width * .82f, size.height * .2f)
      lineTo(size.width * .78f, size.height * .62f)
      quadraticTo(size.width * .72f, size.height * .82f, size.width * .5f, size.height * .94f)
      quadraticTo(size.width * .28f, size.height * .82f, size.width * .22f, size.height * .62f)
      lineTo(size.width * .18f, size.height * .2f)
      close()
    }
    drawPath(shield, color = color, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
    if (checked && progress > .5f) {
      drawLine(
        color = color,
        start = Offset(size.width * .34f, size.height * .52f),
        end = Offset(size.width * .46f, size.height * .64f),
        strokeWidth = 4.dp.toPx(),
        cap = StrokeCap.Round,
      )
      drawLine(
        color = color,
        start = Offset(size.width * .46f, size.height * .64f),
        end = Offset(size.width * .68f, size.height * .39f),
        strokeWidth = 4.dp.toPx(),
        cap = StrokeCap.Round,
      )
    }
  }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun MainScreenPreview() {
  VeilarkTheme(dynamicColor = false) {
    MainScreen(profileName = "Veilark · 10 узлов")
  }
}
