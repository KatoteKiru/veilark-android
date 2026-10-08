@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.example.veilark.ui.main

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.example.veilark.BuildConfig
import com.example.veilark.R
import com.example.veilark.diagnostics.TechnicalLogEntry
import com.example.veilark.profile.ConnectionNode
import com.example.veilark.profile.InstalledApp
import com.example.veilark.profile.InstalledAppLoader
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.profile.SingBoxCatalogEntry
import com.example.veilark.protocol.allowsProfileSwitch
import com.example.veilark.theme.VeilarkTheme
import com.example.veilark.vpn.ConnectionState
import com.example.veilark.vpn.StartupStage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SubscriptionUiItem(
  val id: String,
  val name: String,
  val nodeCount: Int,
  val refreshable: Boolean,
  val deletable: Boolean,
  val shareLink: String? = null,
)

private enum class LegalDocument(
  val titleRes: Int,
  val contentRes: Int,
) {
  Veilark(R.string.veilark_license_title, R.raw.gpl_3_0),
  TrustTunnel(R.string.trust_license_title, R.raw.apache_2_0),
  ThirdParty(R.string.third_party_notices_title, R.raw.third_party_notices),
}

@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  profileName: String? = null,
  connectionState: ConnectionState = ConnectionState.Disconnected,
  startupStage: StartupStage = StartupStage.Idle,
  importError: String? = null,
  routingNotice: String? = null,
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
  geoUpdating: Boolean = false,
  geoUpdateMessage: String? = null,
  trustTunnelActive: Boolean = false,
  singBoxAvailable: Boolean = true,
  trustTunnelAvailable: Boolean = false,
  engineDescription: String = "sing-box 1.13.21",
  subscriptionRefreshAvailable: Boolean = false,
  refreshingSubscription: Boolean = false,
  selfUpdateEnabled: Boolean = true,
  updateStatus: String = "",
  updateNotes: String = "",
  updateAvailable: Boolean = false,
  updateNoticeRequest: Long = 0L,
  updating: Boolean = false,
  updateProgress: Float? = null,
  importing: Boolean = false,
  externalImportUrl: String? = null,
  onExternalImportConsumed: () -> Unit = {},
  onImportFile: () -> Unit = {},
  onImportUrl: (String) -> Unit = {},
  onScanQr: ((String) -> Unit) -> Unit = {},
  onSelectNode: (String) -> Unit = {},
  onSelectSubscription: (String) -> Unit = {},
  onRefreshSubscription: () -> Unit = {},
  onDeleteSubscription: (String) -> Unit = {},
  onShareQr: (String, String) -> Unit = { _, _ -> },
  onSwitchProfile: () -> Unit = {},
  onRefreshLatency: () -> Unit = {},
  onOpenRouting: () -> Unit = {},
  onRefreshGeo: () -> Unit = {},
  onApplyRouting: (String, String, String, String, String, Set<String>) -> Boolean =
    { _, _, _, _, _, _ -> true },
  onCopyDiagnostic: () -> Unit = {},
  onClearTechnicalLogs: () -> Unit = {},
  onRunDiagnostics: () -> Unit = {},
  onOpenSubscriptionAccount: () -> Boolean = { false },
  onOpenWebAccount: () -> Boolean = { false },
  onCheckUpdate: () -> Unit = {},
  onUpdate: () -> Unit = {},
  onConnect: () -> Unit = {},
) {
  // Screen and overlay flags survive rotation, process death and dark-mode
  // switches; transient bookkeeping for in-flight imports stays in remember.
  var showImport by rememberSaveable { mutableStateOf(false) }
  val mainList = rememberLazyListState()
  var consumedNoticeRequest by rememberSaveable { mutableLongStateOf(0L) }
  LaunchedEffect(updateNoticeRequest) {
    if (updateNoticeRequest > 0 && selfUpdateEnabled) onCheckUpdate()
  }
  LaunchedEffect(updateNoticeRequest, updateAvailable) {
    if (updateNoticeRequest > 0 && updateAvailable && selfUpdateEnabled) {
      mainList.animateScrollToItem((mainList.layoutInfo.totalItemsCount - 2).coerceAtLeast(0))
    }
  }
  var showNodes by rememberSaveable { mutableStateOf(false) }
  var showRouting by rememberSaveable { mutableStateOf(false) }
  var showTechnicalLogs by rememberSaveable { mutableStateOf(false) }
  var showAbout by rememberSaveable { mutableStateOf(false) }
  var legalDocument by rememberSaveable { mutableStateOf<LegalDocument?>(null) }
  var subscriptionUrl by rememberSaveable { mutableStateOf("") }
  var importAttempted by rememberSaveable { mutableStateOf(false) }
  var importSubmitted by remember { mutableStateOf(false) }
  var observedImporting by remember { mutableStateOf(false) }
  var addMenuExpanded by rememberSaveable { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }
  val coroutineScope = rememberCoroutineScope()
  val subscriptionBotOpenFailed = stringResource(R.string.subscription_bot_open_failed)
  val openSubscriptionAccount = {
    if (!onOpenSubscriptionAccount()) {
      coroutineScope.launch { snackbarHostState.showSnackbar(subscriptionBotOpenFailed) }
    }
  }
  val webAccountOpenFailed = stringResource(R.string.web_account_open_failed)
  val openWebAccount = {
    if (!onOpenWebAccount()) {
      coroutineScope.launch { snackbarHostState.showSnackbar(webAccountOpenFailed) }
    }
  }
  val routingSavedMessage = stringResource(R.string.routing_saved)
  val routingSavedReconnectMessage = stringResource(R.string.routing_saved_reconnect)
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
          shareLink = subscription.sourceUrl,
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

  // A validated deep link uses the same import callback as the reviewed manual
  // surface. LaunchedEffect makes this one-shot for each request value; clearing
  // the request immediately prevents recomposition from importing it twice.
  LaunchedEffect(externalImportUrl) {
    externalImportUrl?.let { value ->
      subscriptionUrl = value
      importAttempted = true
      importSubmitted = true
      showImport = true
      onExternalImportConsumed()
      onImportUrl(value)
    }
  }

  val scanAndImport = {
    onScanQr { scanned ->
      val value = scanned.trim()
      subscriptionUrl = value
      importAttempted = true
      importSubmitted = true
      showImport = true
      onImportUrl(value)
    }
  }
  val pasteAndReview = {
    subscriptionUrl = clipboard.getText()?.text?.trim().orEmpty()
    importAttempted = false
    showImport = true
  }
  val openImport = {
    subscriptionUrl = ""
    importAttempted = false
    showImport = true
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

  if (showAbout) {
    val document = legalDocument
    if (document == null) {
      AboutScreen(
        onBack = { showAbout = false },
        onOpenDocument = { legalDocument = it },
        onOpenSubscriptionAccount = openSubscriptionAccount,
        onOpenWebAccount = openWebAccount,
      )
    } else {
      LegalDocumentScreen(
        document = document,
        onBack = { legalDocument = null },
      )
    }
    return
  }

  // System and predictive back first collapse the add menu before leaving.
  BackHandler(enabled = addMenuExpanded) { addMenuExpanded = false }

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
      onScanQr = scanAndImport,
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
      geoRefreshEnabled = connectionState == ConnectionState.Disconnected ||
        connectionState == ConnectionState.Failed,
      geoUpdating = geoUpdating,
      geoUpdateMessage = geoUpdateMessage,
      onRefreshGeo = onRefreshGeo,
      onApply = { route, direct, vpn, apps, dpi, packages ->
        val reconnectRequired = connectionState != ConnectionState.Disconnected &&
          connectionState != ConnectionState.Failed
        if (onApplyRouting(route, direct, vpn, apps, dpi, packages)) {
          showRouting = false
          coroutineScope.launch {
            snackbarHostState.showSnackbar(
              if (reconnectRequired) {
                routingSavedReconnectMessage
              } else {
                routingSavedMessage
              },
            )
          }
        }
      },
      onDismiss = { showRouting = false },
    )
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "Veilark",
            style = MaterialTheme.typography.titleLargeEmphasized,
          )
        },
        actions = {
          CompactIconAction(
            glyph = ActionGlyph.Journal,
            description = stringResource(R.string.open_technical_log),
            onClick = { showTechnicalLogs = true },
          )
          CompactIconAction(
            glyph = ActionGlyph.Info,
            description = stringResource(R.string.open_about),
            onClick = { showAbout = true },
          )
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
        ),
      )
    },
    floatingActionButton = {
      AddProfileFabMenu(
        expanded = addMenuExpanded,
        enabled = !importing,
        onExpandedChange = { addMenuExpanded = it },
        onEnterLink = openImport,
        onPaste = pasteAndReview,
        onScanQr = scanAndImport,
        onImportFile = onImportFile,
      )
    },
  ) { innerPadding ->
    LazyColumn(
      state = mainList,
      modifier = Modifier
        .testTag("main_content_list")
        .fillMaxHeight()
        .fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = 720.dp),
      // Scaffold's inner padding carries the system bar and display-cutout
      // insets on every edge; the extra bottom space keeps the last card clear
      // of the floating add button.
      contentPadding = innerPadding.plusContent(horizontal = 16.dp, top = 12.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
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
          onConnect = if (profileName == null) openImport else onConnect,
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
      if (routingNotice != null) {
        item {
          RoutingNoticeCard(message = routingNotice)
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
          onAddSubscription = openImport,
          onPasteSubscription = pasteAndReview,
          onScanSubscriptionQr = scanAndImport,
          onDeleteSubscription = onDeleteSubscription,
          onShareQr = onShareQr,
          onOpenSubscriptionAccount = openSubscriptionAccount,
          onOpenWebAccount = openWebAccount,
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
              openImport()
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
            noticeRequest = updateNoticeRequest,
            consumedNoticeRequest = consumedNoticeRequest,
            onNoticeConsumed = { consumedNoticeRequest = it },
          )
        }
      }
      item {
        Text(
          text = stringResource(R.string.core_footer, engineDescription),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

/**
 * Adds content spacing on top of Scaffold/system insets for every edge, so
 * lists respect the navigation bar, gesture area and landscape cutouts.
 */
@Composable
private fun PaddingValues.plusContent(horizontal: Dp, top: Dp, bottom: Dp): PaddingValues {
  val direction = LocalLayoutDirection.current
  return PaddingValues(
    start = calculateStartPadding(direction) + horizontal,
    top = calculateTopPadding() + top,
    end = calculateEndPadding(direction) + horizontal,
    bottom = calculateBottomPadding() + bottom,
  )
}

/**
 * Material 3 Expressive FAB menu for every way of adding a subscription or
 * profile. It replaces the former icon-only "add" action in the top bar.
 */
@Composable
private fun AddProfileFabMenu(
  expanded: Boolean,
  enabled: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  onEnterLink: () -> Unit,
  onPaste: () -> Unit,
  onScanQr: () -> Unit,
  onImportFile: () -> Unit,
) {
  val openDescription = stringResource(R.string.add_subscription_or_profile)
  val closeDescription = stringResource(R.string.fab_menu_close)
  val stateDescriptionText = stringResource(if (expanded) R.string.expanded else R.string.collapsed)
  FloatingActionButtonMenu(
    expanded = expanded,
    button = {
      ToggleFloatingActionButton(
        checked = expanded,
        onCheckedChange = { if (enabled || !it) onExpandedChange(it) },
        modifier = Modifier.semantics {
          contentDescription = if (expanded) closeDescription else openDescription
          stateDescription = stateDescriptionText
          role = Role.Button
        },
      ) {
        val progress = { checkedProgress }
        Icon(
          imageVector = if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add,
          contentDescription = null,
          modifier = Modifier.animateIcon(progress),
        )
      }
    },
  ) {
    val entries = listOf(
      Triple(Icons.Rounded.Link, R.string.action_enter_link, onEnterLink),
      Triple(Icons.Rounded.ContentPaste, R.string.action_paste, onPaste),
      Triple(Icons.Rounded.QrCodeScanner, R.string.action_scan_qr, onScanQr),
      Triple(Icons.Rounded.Description, R.string.action_choose_file, onImportFile),
    )
    entries.forEach { (icon, label, action) ->
      FloatingActionButtonMenuItem(
        onClick = {
          onExpandedChange(false)
          action()
        },
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(stringResource(label)) },
      )
    }
  }
}

// LinearWavyProgressIndicator is still library-group restricted in material3
// 1.5.0-alpha18 (public from alpha19, which needs compileSdk 37).
@SuppressLint("RestrictedApi")
@Composable
private fun UpdateCard(
  status: String,
  notes: String,
  available: Boolean,
  updating: Boolean,
  progress: Float?,
  onCheck: () -> Unit,
  onUpdate: () -> Unit,
  noticeRequest: Long = 0L,
  consumedNoticeRequest: Long = 0L,
  onNoticeConsumed: (Long) -> Unit = {},
) {
  var showConfirmation by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(noticeRequest, available) {
    if (noticeRequest > consumedNoticeRequest && available) {
      showConfirmation = true
      onNoticeConsumed(noticeRequest)
    }
  }
  val normalizedProgress = progress?.coerceIn(0f, 1f)
  if (showConfirmation && available && !updating) {
    AlertDialog(
      onDismissRequest = { showConfirmation = false },
      icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
      title = { Text(stringResource(R.string.update_confirm_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(status, style = MaterialTheme.typography.titleSmallEmphasized)
          Text(
            text = notes.ifBlank { stringResource(R.string.update_notes_missing) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = stringResource(R.string.update_verification_note),
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
          shapes = ButtonDefaults.shapes(),
        ) {
          Icon(
            Icons.Rounded.Download,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
          )
          Spacer(Modifier.size(ButtonDefaults.IconSpacing))
          Text(stringResource(R.string.update))
        }
      },
      dismissButton = {
        TextButton(onClick = { showConfirmation = false }, shapes = ButtonDefaults.shapes()) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }
  Column {
    SectionHeading(stringResource(R.string.updates))
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
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
            Text("Veilark", style = MaterialTheme.typography.titleSmallEmphasized)
            Text(
              status,
              modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
              updating -> stringResource(R.string.update_downloading)
              available -> stringResource(R.string.update_show_and_download)
              else -> stringResource(R.string.update_check_action)
            },
            onClick = if (available) ({ showConfirmation = true }) else onCheck,
            enabled = !updating,
            filled = available && !updating,
          )
        }
        if (updating) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            if (normalizedProgress != null) {
              val animatedProgress by animateFloatAsState(
                targetValue = normalizedProgress,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "update download progress",
              )
              LinearWavyProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.weight(1f),
              )
              Text(
                text = "${(normalizedProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
            }
          }
        }
      }
    }
  }
}

/** Emblem outline for each connection state (Material 3 Expressive shapes). */
private fun emblemPolygon(connected: Boolean): RoundedPolygon =
  if (connected) MaterialShapes.Cookie12Sided else MaterialShapes.Cookie4Sided

/**
 * A [Shape] that renders a [Morph] between two Material shapes at [progress],
 * scaled to the component bounds and centred. Used for the connection emblem.
 */
private class MorphShape(
  private val morph: Morph,
  private val progress: Float,
) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val path = morph.toPath(progress = progress)
    path.transform(Matrix().apply { scale(x = size.width, y = size.height) })
    path.translate(size.center - path.getBounds().center)
    return Outline.Generic(path)
  }

  override fun equals(other: Any?): Boolean =
    other is MorphShape && other.morph === morph && other.progress == progress

  override fun hashCode(): Int = 31 * System.identityHashCode(morph) + progress.hashCode()
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
  val failed = state == ConnectionState.Failed
  val motion = MaterialTheme.motionScheme
  // The emblem morphs from a soft square into a 12-sided "seal" when the tunnel
  // is up. The morph only animates on a state change; nothing loops at rest.
  val emblemMorph = remember { Morph(emblemPolygon(false), emblemPolygon(true)) }
  val morphProgress by animateFloatAsState(
    targetValue = if (connected) 1f else 0f,
    animationSpec = motion.slowSpatialSpec(),
    label = "connection emblem morph",
  )
  val emblemRotation by animateFloatAsState(
    targetValue = if (connected) 45f else 0f,
    animationSpec = motion.slowSpatialSpec(),
    label = "connection emblem rotation",
  )
  val container by animateColorAsState(
    targetValue = when {
      connected -> MaterialTheme.colorScheme.primaryContainer
      failed -> MaterialTheme.colorScheme.errorContainer
      else -> MaterialTheme.colorScheme.surfaceContainerHigh
    },
    animationSpec = motion.defaultEffectsSpec(),
    label = "connection surface",
  )
  val corner by animateDpAsState(
    targetValue = if (connected) 40.dp else 32.dp,
    animationSpec = motion.defaultSpatialSpec(),
    label = "connection shape",
  )
  val emblemColor by animateColorAsState(
    targetValue = if (connected) {
      MaterialTheme.colorScheme.primary
    } else {
      MaterialTheme.colorScheme.surfaceContainerLowest
    },
    animationSpec = motion.defaultEffectsSpec(),
    label = "connection emblem color",
  )
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(corner),
    color = container,
    contentColor = when {
      connected -> MaterialTheme.colorScheme.onPrimaryContainer
      failed -> MaterialTheme.colorScheme.onErrorContainer
      else -> MaterialTheme.colorScheme.onSurface
    },
  ) {
    Column(
      modifier = Modifier.padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          Modifier
            .matchParentSize()
            .graphicsLayer { rotationZ = emblemRotation }
            .clip(MorphShape(emblemMorph, morphProgress))
            .background(emblemColor),
        )
        if (connecting) {
          LoadingIndicator(
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.primary,
          )
        } else {
          VeilarkMark(
            color = if (connected) {
              MaterialTheme.colorScheme.onPrimary
            } else {
              MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(50.dp),
          )
        }
      }
      Spacer(Modifier.height(20.dp))
      AnimatedContent(
        targetState = state,
        transitionSpec = {
          (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 3 }) togetherWith
            (fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { -it / 3 })
        },
        // Announce every connection state change to accessibility services.
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        label = "connection state",
      ) { current ->
        Text(
          text = when (current) {
            ConnectionState.Disconnected -> stringResource(R.string.vpn_off)
            ConnectionState.Connecting -> stringResource(stage.titleRes)
            ConnectionState.Connected -> stringResource(R.string.vpn_protected)
            ConnectionState.Failed -> stringResource(R.string.vpn_connect_failed)
          },
          style = MaterialTheme.typography.headlineMediumEmphasized,
          textAlign = TextAlign.Center,
        )
      }
      Spacer(Modifier.height(6.dp))
      Text(
        text = when {
          profileName == null -> stringResource(R.string.vpn_add_profile_hint)
          connecting -> stringResource(R.string.vpn_connecting_hint)
          connected -> profileName
          else -> stringResource(R.string.vpn_ready_hint)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      if (connected && latency != null) {
        Spacer(Modifier.height(8.dp))
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        ) {
          Text(
            text = stringResource(R.string.latency_ms, latency),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
      Spacer(Modifier.height(24.dp))
      // Medium expressive button: round at rest, morphs to its pressed shape.
      val buttonHeight = ButtonDefaults.MediumContainerHeight
      Button(
        onClick = onConnect,
        shapes = ButtonDefaults.shapesFor(buttonHeight),
        enabled = !importing,
        modifier = Modifier.fillMaxWidth().heightIn(min = buttonHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight, hasStartIcon = true),
        colors = if (connected || connecting) {
          ButtonDefaults.filledTonalButtonColors()
        } else {
          ButtonDefaults.buttonColors()
        },
      ) {
        val iconSize = ButtonDefaults.iconSizeFor(buttonHeight)
        if (importing) {
          // "Checking profile" is a progress state, not an add/power action.
          LoadingIndicator(
            modifier = Modifier.size(iconSize + 8.dp),
            color = LocalContentColor.current,
          )
        } else {
          Icon(
            imageVector = when {
              profileName == null -> Icons.Rounded.Add
              connecting -> Icons.Rounded.Close
              else -> Icons.Rounded.PowerSettingsNew
            },
            contentDescription = null,
            modifier = Modifier.size(iconSize),
          )
        }
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(buttonHeight)))
        Text(
          text = when {
            importing -> stringResource(R.string.profile_checking)
            profileName == null -> stringResource(R.string.profile_add)
            connecting -> stringResource(R.string.connection_cancel)
            connected -> stringResource(R.string.disconnect)
            else -> stringResource(R.string.connect)
          },
          style = ButtonDefaults.textStyleFor(buttonHeight),
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
  val fontScale = LocalDensity.current.fontScale
  Column {
    SectionHeading(stringResource(R.string.connection_mode))
    Surface(
      modifier = Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
      BoxWithConstraints(Modifier.padding(12.dp)) {
        val stackModes = fontScale >= 1.3f || maxWidth < 300.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          val singBox: @Composable (Modifier, ToggleButtonShapes) -> Unit = { mod, shapes ->
            EngineModeButton(
              title = "sing-box",
              trust = false,
              selected = !trustTunnelActive,
              enabled = idle && singBoxAvailable,
              shapes = shapes,
              modifier = mod,
              onClick = { if (trustTunnelActive && canSwitch) onSwitchProfile() },
            )
          }
          val trust: @Composable (Modifier, ToggleButtonShapes) -> Unit = { mod, shapes ->
            EngineModeButton(
              title = "TrustTunnel",
              trust = true,
              selected = trustTunnelActive,
              enabled = idle && trustTunnelAvailable,
              shapes = shapes,
              modifier = mod,
              onClick = { if (!trustTunnelActive && canSwitch) onSwitchProfile() },
            )
          }
          if (stackModes) {
            // Large font scale / narrow width: stack the two modes, each with
            // the standard toggle shapes so neither reads as a clipped half.
            Column(
              modifier = Modifier.selectableGroup(),
              verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
              singBox(Modifier.fillMaxWidth(), ToggleButtonDefaults.shapes())
              trust(Modifier.fillMaxWidth(), ToggleButtonDefaults.shapes())
            }
          } else {
            // Connected button group: two toggle buttons that share an edge and
            // morph (pressed / checked) with the expressive motion scheme.
            Row(
              modifier = Modifier.fillMaxWidth().selectableGroup(),
              horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
              singBox(Modifier.weight(1f), ButtonGroupDefaults.connectedLeadingButtonShapes())
              trust(Modifier.weight(1f), ButtonGroupDefaults.connectedTrailingButtonShapes())
            }
          }
          Text(
            text = when {
              !idle ->
                stringResource(R.string.engine_switch_disconnect_first)
              !alternativeAvailable ->
                stringResource(R.string.engine_switch_add_profile)
              trustTunnelActive ->
                stringResource(R.string.engine_trust_summary)
              else ->
                stringResource(R.string.engine_singbox_summary)
            },
            modifier = Modifier.padding(horizontal = 4.dp),
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
  shapes: ToggleButtonShapes,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  val colors = ToggleButtonDefaults.toggleButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    checkedContainerColor = MaterialTheme.colorScheme.primary,
    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
  )
  ToggleButton(
    checked = selected,
    onCheckedChange = { if (!selected) onClick() },
    // The current engine stays fully legible while switching is locked.
    enabled = enabled || selected,
    shapes = shapes,
    colors = colors,
    modifier = modifier
      .heightIn(min = 56.dp)
      // Outermost semantics win over ToggleButton's Checkbox role: this is a
      // single choice between two engines, i.e. a radio group.
      .semantics {
        role = Role.RadioButton
        this.selected = selected
      },
  ) {
    EngineGlyph(trust = trust, color = LocalContentColor.current, modifier = Modifier.size(22.dp))
    Spacer(Modifier.size(9.dp))
    Text(
      title,
      style = if (selected) {
        MaterialTheme.typography.titleSmallEmphasized
      } else {
        MaterialTheme.typography.titleSmall
      },
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * Shared frame for secondary screens: a large flexible (expressive) top app bar
 * that collapses on scroll, system/predictive back handling, and content
 * padding that respects every system inset.
 */
@Composable
private fun SecondaryScreen(
  title: String,
  backDescription: String,
  onBack: () -> Unit,
  actions: @Composable RowScope.() -> Unit = {},
  content: @Composable (PaddingValues) -> Unit,
) {
  BackHandler(onBack = onBack)
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  Scaffold(
    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      LargeFlexibleTopAppBar(
        title = {
          Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
          CompactIconAction(
            glyph = ActionGlyph.Back,
            description = backDescription,
            onClick = onBack,
          )
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
          scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
      )
    },
    content = content,
  )
}

@Composable
private fun AboutScreen(
  onBack: () -> Unit,
  onOpenDocument: (LegalDocument) -> Unit,
  onOpenSubscriptionAccount: () -> Unit,
  onOpenWebAccount: () -> Unit,
) {
  val uriHandler = LocalUriHandler.current
  val privacyPolicyUrl = stringResource(R.string.privacy_policy_url)
  SecondaryScreen(
    title = stringResource(R.string.about_title),
    backDescription = stringResource(R.string.back),
    onBack = onBack,
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = innerPadding.plusContent(horizontal = 20.dp, top = 12.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = stringResource(R.string.about_summary),
            style = MaterialTheme.typography.titleMediumEmphasized,
          )
          Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      item {
        AboutLinkCard(
          title = stringResource(R.string.web_account_open),
          description = stringResource(R.string.web_account_note),
          onClick = onOpenWebAccount,
          prominent = true,
        )
      }
      item {
        AboutLinkCard(
          title = stringResource(R.string.subscription_get_or_renew),
          description = stringResource(R.string.subscription_bot_note),
          onClick = onOpenSubscriptionAccount,
        )
      }
      item {
        AboutLinkCard(
          title = stringResource(R.string.source_code),
          description = stringResource(R.string.source_code_description),
          onClick = { uriHandler.openUri("https://github.com/KatoteKiru/veilark-android") },
          linkDescription = true,
        )
      }
      item {
        AboutLinkCard(
          title = stringResource(R.string.privacy_policy),
          description = stringResource(R.string.privacy_policy_description),
          onClick = { uriHandler.openUri(privacyPolicyUrl) },
          linkDescription = true,
        )
      }
      item {
        Text(
          text = stringResource(R.string.open_source_licenses),
          modifier = Modifier.semantics { heading() },
          style = MaterialTheme.typography.titleMediumEmphasized,
        )
      }
      item {
        Text(
          text = stringResource(R.string.trust_attribution),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      items(LegalDocument.entries, key = LegalDocument::name) { document ->
        LegalDocumentItem(
          title = stringResource(document.titleRes),
          onClick = { onOpenDocument(document) },
        )
      }
      item {
        Text(
          text = stringResource(R.string.independent_project_notice),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * A rounded, tappable card. Surface(onClick) clips the ripple to the card
 * shape and exposes the Button role, unlike Modifier.clickable on a Surface.
 */
@Composable
private fun AboutLinkCard(
  title: String,
  description: String,
  onClick: () -> Unit,
  prominent: Boolean = false,
  linkDescription: Boolean = false,
) {
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
    shape = MaterialTheme.shapes.medium,
    color = if (prominent) {
      MaterialTheme.colorScheme.secondaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    },
    contentColor = if (prominent) {
      MaterialTheme.colorScheme.onSecondaryContainer
    } else {
      MaterialTheme.colorScheme.onSurface
    },
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(text = title, style = MaterialTheme.typography.titleSmallEmphasized)
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = when {
          prominent -> MaterialTheme.colorScheme.onSecondaryContainer
          linkDescription -> MaterialTheme.colorScheme.primary
          else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
      )
    }
  }
}

@Composable
private fun LegalDocumentItem(
  title: String,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.Description,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = title,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyLargeEmphasized,
      )
    }
  }
}

@Composable
private fun LegalDocumentScreen(
  document: LegalDocument,
  onBack: () -> Unit,
) {
  val resources = LocalResources.current
  val content = remember(document, resources.configuration) {
    resources.openRawResource(document.contentRes)
      .bufferedReader(Charsets.UTF_8)
      .use { it.readText() }
  }
  SecondaryScreen(
    title = stringResource(document.titleRes),
    backDescription = stringResource(R.string.legal_document_back),
    onBack = onBack,
  ) { innerPadding ->
    SelectionContainer {
      Text(
        text = content,
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(innerPadding.plusContent(horizontal = 20.dp, top = 12.dp, bottom = 32.dp)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun TechnicalLogScreen(
  entries: List<TechnicalLogEntry>,
  onBack: () -> Unit,
  onClear: () -> Unit,
  onRunDiagnostics: () -> Unit,
) {
  SecondaryScreen(
    title = stringResource(R.string.technical_log),
    backDescription = stringResource(R.string.back),
    onBack = onBack,
    actions = {
      CompactIconAction(
        glyph = ActionGlyph.Diagnostics,
        description = stringResource(R.string.run_diagnostics),
        onClick = onRunDiagnostics,
      )
      CompactIconAction(
        glyph = ActionGlyph.Clear,
        description = stringResource(R.string.clear_log),
        enabled = entries.isNotEmpty(),
        onClick = onClear,
      )
    },
  ) { innerPadding ->
    if (entries.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          stringResource(R.string.technical_log_empty),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    } else {
      // Newest first; keys stay unique even for byte-identical entries.
      val keyed = remember(entries) { entries.zip(technicalLogKeys(entries)).asReversed() }
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = innerPadding.plusContent(horizontal = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(keyed, key = { it.second }) { (entry, _) ->
          LogEntryCard(entry)
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
    shape = MaterialTheme.shapes.medium,
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
  onShareQr: (String, String) -> Unit,
  onOpenSubscriptionAccount: () -> Unit,
  onOpenWebAccount: () -> Unit,
  onRefreshLatency: () -> Unit,
  onSelectNode: (String) -> Unit,
  onSelectSubscription: (String) -> Unit,
  onToggleNodes: () -> Unit,
) {
  val selectedNode = nodes.firstOrNull { it.tag == selectedTag }
  var pendingDeletionId by remember { mutableStateOf<String?>(null) }
  val pendingDeletion = subscriptions.firstOrNull { it.id == pendingDeletionId }
  val expansionStateDescription = stringResource(
    if (expanded) R.string.expanded else R.string.collapsed,
  )

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
    SectionHeading(
      text = stringResource(
        if (trustTunnelActive) R.string.trust_profile else R.string.singbox_profile,
      ),
    )
    Surface(
      onClick = onToggleNodes,
      modifier = Modifier
        .fillMaxWidth()
        .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
        .semantics {
          role = Role.Button
          stateDescription = expansionStateDescription
        },
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
      Column {
        ListItem(
          headlineContent = {
            Text(
              profileName ?: stringResource(R.string.profile_missing),
              style = MaterialTheme.typography.bodyLargeEmphasized,
            )
          },
          supportingContent = {
            Text(
              when {
                profileName == null -> stringResource(R.string.import_subscription)
                selectedNode != null && !trustTunnelActive ->
                  "${selectedNode.name} · ${selectedNode.protocol}"
                selectedNode != null -> selectedNode.protocol
            nodes.isNotEmpty() -> pluralStringResource(
              R.plurals.automatic_node_summary,
              nodes.size,
              nodes.size,
            )
            else -> stringResource(R.string.imported_configuration)
              },
            )
          },
          leadingContent = {
            Surface(
              modifier = Modifier.size(44.dp),
              shape = MaterialShapes.Cookie4Sided.toShape(),
              color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
              Box(contentAlignment = Alignment.Center) {
                val motion = MaterialTheme.motionScheme
                AnimatedContent(
                  targetState = trustTunnelActive,
                  transitionSpec = {
                    fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                  },
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
        onShareQr = onShareQr,
        onOpenSubscriptionAccount = onOpenSubscriptionAccount,
        onOpenWebAccount = onOpenWebAccount,
      )
    }
  }
}

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
  onShareQr: (String, String) -> Unit,
  onOpenSubscriptionAccount: () -> Unit,
  onOpenWebAccount: () -> Unit,
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

      // Expressive floating toolbar grouping the sheet's subscription actions.
      val actionsLabel = stringResource(R.string.connection_picker_actions)
      HorizontalFloatingToolbar(
        expanded = true,
        modifier = Modifier
          .align(Alignment.CenterHorizontally)
          .padding(horizontal = 16.dp, vertical = 12.dp)
          .semantics { contentDescription = actionsLabel },
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
          toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        expandedShadowElevation = 0.dp,
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

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        TextButton(
          onClick = onOpenWebAccount,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.web_account_open))
        }
        TextButton(
          onClick = onOpenSubscriptionAccount,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.subscription_get_or_renew))
        }
      }
      Text(
        text = stringResource(R.string.subscription_bot_note),
        modifier = Modifier
          .align(Alignment.CenterHorizontally)
          .padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )

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
              onShare = subscription.shareLink?.let { payload ->
                { onShareQr(subscription.name, payload) }
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
              latencies[node.tag]?.let {
                append(" · ")
                append(stringResource(R.string.latency_ms, it))
              }
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
  Info,
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
  filled: Boolean = false,
) {
  val iconModifier = modifier
    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    .semantics { contentDescription = description }
  val content: @Composable () -> Unit = {
    if (loading) {
      LoadingIndicator(
        modifier = Modifier.size(28.dp),
        color = LocalContentColor.current,
      )
    } else {
      Icon(
        imageVector = glyph.imageVector(),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
      )
    }
  }
  // Expressive icon buttons: round at rest, squircle while pressed.
  if (filled) {
    FilledTonalIconButton(
      onClick = onClick,
      enabled = enabled,
      shapes = IconButtonDefaults.shapes(),
      modifier = iconModifier,
      content = content,
    )
  } else {
    IconButton(
      onClick = onClick,
      enabled = enabled,
      shapes = IconButtonDefaults.shapes(),
      modifier = iconModifier,
      colors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
      ),
      content = content,
    )
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
  ActionGlyph.Info -> Icons.Rounded.Info
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
      // Protocol glyph: the two endpoint lanes represent the sing-box engine.
      drawLine(color, Offset(size.width * .22f, size.height * .30f), Offset(size.width * .78f, size.height * .30f), strokeWidth = stroke.width, cap = StrokeCap.Round)
      drawLine(color, Offset(size.width * .22f, size.height * .70f), Offset(size.width * .78f, size.height * .70f), strokeWidth = stroke.width, cap = StrokeCap.Round)
      drawCircle(color, radius = size.minDimension * .07f, center = Offset(size.width * .35f, size.height * .50f))
      drawCircle(color, radius = size.minDimension * .07f, center = Offset(size.width * .65f, size.height * .50f))
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
    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
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
private fun SectionHeading(text: String) {
  Text(
    text = text,
    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() },
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
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
    SectionHeading(stringResource(R.string.routing))
    Surface(
      onClick = onClick,
      enabled = available,
      modifier = Modifier.fillMaxWidth().testTag("routing_card").semantics { role = Role.Button },
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
      Column {
        if (available) {
          InfoRow(
            stringResource(R.string.traffic),
            when (routingMode) {
              ProfileSelection.ROUTING_RU_DIRECT -> stringResource(R.string.russia_direct)
              ProfileSelection.ROUTING_MANUAL -> stringResource(R.string.custom_rules)
              else -> stringResource(R.string.all_traffic_vpn)
            },
          )
          HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
          InfoRow(
            stringResource(R.string.applications),
            when (applicationMode) {
              ProfileSelection.APPS_ONLY -> pluralStringResource(
                R.plurals.only_selected_count,
                selectedApplicationCount,
                selectedApplicationCount,
              )
              ProfileSelection.APPS_BYPASS -> pluralStringResource(
                R.plurals.bypass_selected_count,
                selectedApplicationCount,
                selectedApplicationCount,
              )
              else -> stringResource(R.string.all_apps)
            },
          )
          HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
          InfoRow(
            stringResource(R.string.dpi_protection),
            if (dpiMode == ProfileSelection.DPI_TLS_FRAGMENT) {
              stringResource(R.string.tls_fragmentation)
            } else {
              stringResource(R.string.standard)
            },
          )
          HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
          InfoRow(stringResource(R.string.dns), stringResource(R.string.protected_value))
          HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
          InfoRow(stringResource(R.string.kill_switch), stringResource(R.string.enabled_value))
        } else {
          InfoRow(stringResource(R.string.traffic), stringResource(R.string.trust_full_tunnel))
          HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
          InfoRow(stringResource(R.string.protection), "H2/H3 · Anti-DPI · Kill switch")
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
  onShare: (() -> Unit)?,
) {
  val (container, shape) = animatedSelection(
    selected = selected,
    selectedColor = MaterialTheme.colorScheme.secondaryContainer,
    unselectedColor = Color.Transparent,
    label = "subscription",
  )
  Surface(
    selected = selected,
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
    shape = shape,
    color = container,
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
          style = MaterialTheme.typography.bodyLargeEmphasized,
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
      if (onShare != null) {
        CompactIconAction(
          glyph = ActionGlyph.Qr,
          description = stringResource(R.string.subscription_share_qr, name),
          onClick = onShare,
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
  val (container, shape) = animatedSelection(
    selected = selected,
    selectedColor = MaterialTheme.colorScheme.secondaryContainer,
    unselectedColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    label = "node",
  )
  Surface(
    selected = selected,
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .semantics { role = Role.RadioButton },
    shape = shape,
    color = container,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SelectionIndicator(selected)
      Column(Modifier.weight(1f)) {
        Text(
          title,
          style = if (selected) {
            MaterialTheme.typography.bodyLargeEmphasized
          } else {
            MaterialTheme.typography.bodyLarge
          },
        )
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
  geoRefreshEnabled: Boolean,
  geoUpdating: Boolean,
  geoUpdateMessage: String?,
  onRefreshGeo: () -> Unit,
  onApply: (String, String, String, String, String, Set<String>) -> Unit,
  onDismiss: () -> Unit,
) {
  // The draft is seeded once when the dialog opens and is never reset by
  // upstream recomposition (for example the installed-app list loading or a
  // geo update refreshing props), so in-progress edits are not discarded. It
  // is saveable, so rotation or process death keeps the draft as well.
  var route by rememberSaveable { mutableStateOf(routingMode) }
  var direct by rememberSaveable { mutableStateOf(directRoutes) }
  var vpn by rememberSaveable { mutableStateOf(vpnRoutes) }
  var appMode by rememberSaveable { mutableStateOf(applicationMode) }
  var dpi by rememberSaveable { mutableStateOf(dpiMode) }
  var packages by rememberSaveable(stateSaver = StringSetSaver) {
    mutableStateOf(selectedApplications)
  }
  var search by rememberSaveable { mutableStateOf("") }
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
      (route != ProfileSelection.ROUTING_MANUAL ||
        direct.isNotBlank() || vpn.isNotBlank())
  FullScreenDialog(onDismiss = onDismiss) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .fillMaxHeight()
          .widthIn(max = 720.dp)
          .consumeTaps(),
        shape = MaterialTheme.shapes.extraLarge,
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
                stringResource(R.string.routing),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmallEmphasized,
              )
              Text(
                if (trustTunnelActive) "TrustTunnel" else "sing-box",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            CompactIconAction(
              glyph = ActionGlyph.Refresh,
              description = stringResource(R.string.update_geo_data),
              onClick = onRefreshGeo,
              enabled = geoRefreshEnabled && !trustTunnelActive && !geoUpdating,
              loading = geoUpdating,
            )
            CompactIconAction(
              glyph = ActionGlyph.Close,
              description = stringResource(R.string.close_routing),
              onClick = onDismiss,
            )
          }
          LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            item { SettingsSectionTitle(stringResource(R.string.traffic)) }
            if (geoUpdateMessage != null) {
              item {
                Text(
                  text = geoUpdateMessage,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
            item {
              SettingChoice(
                title = stringResource(R.string.all_traffic_vpn),
                selected = route == ProfileSelection.ROUTING_ALL,
                onClick = { route = ProfileSelection.ROUTING_ALL },
              )
            }
            item {
              SettingChoice(
                title = stringResource(R.string.russia_direct),
                subtitle = stringResource(
                  if (trustTunnelActive) {
                    R.string.trust_russia_direct_description
                  } else {
                    R.string.russia_direct_description
                  },
                ),
                selected = route == ProfileSelection.ROUTING_RU_DIRECT,
                onClick = { route = ProfileSelection.ROUTING_RU_DIRECT },
              )
            }
            item {
              SettingChoice(
                title = stringResource(R.string.custom_rules),
                subtitle = stringResource(R.string.custom_rules_description),
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
                  label = { Text(stringResource(R.string.direct_without_vpn)) },
                  supportingText = { Text(stringResource(R.string.routes_input_hint)) },
                  placeholder = { Text("bank.example\n192.0.2.0/24") },
                  minLines = 3,
                  shape = RoundedCornerShape(16.dp),
                )
              }
              item {
                OutlinedTextField(
                  value = vpn,
                  onValueChange = { vpn = it },
                  modifier = Modifier.fillMaxWidth(),
                  label = { Text(stringResource(R.string.always_vpn)) },
                  supportingText = { Text(stringResource(R.string.vpn_routes_priority)) },
                  placeholder = { Text("video.example\n2001:db8::/32") },
                  minLines = 3,
                  shape = RoundedCornerShape(16.dp),
                )
              }
            }
            if (trustTunnelActive) {
              item {
                Surface(
                  shape = RoundedCornerShape(18.dp),
                  color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                  Text(
                    text = stringResource(R.string.trust_app_rules_note),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                  )
                }
              }
            }
            item { SettingsSectionTitle(stringResource(R.string.applications)) }
            item {
              SettingChoice(
                title = stringResource(R.string.all_apps),
                selected = appMode == ProfileSelection.APPS_ALL,
                onClick = { appMode = ProfileSelection.APPS_ALL },
              )
            }
            item {
              SettingChoice(
                title = stringResource(R.string.apps_only_vpn),
                selected = appMode == ProfileSelection.APPS_ONLY,
                onClick = { appMode = ProfileSelection.APPS_ONLY },
              )
            }
            item {
              SettingChoice(
                title = stringResource(R.string.apps_bypass_vpn),
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
                    if (packages.isEmpty()) {
                      stringResource(R.string.nothing_selected)
                    } else {
                      pluralStringResource(
                        R.plurals.selected_apps_count,
                        packages.size,
                        packages.size,
                      )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  if (packages.isNotEmpty()) {
                    CompactIconAction(
                      glyph = ActionGlyph.Clear,
                      description = stringResource(R.string.clear_selected_apps),
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
                  label = { Text(stringResource(R.string.find_application)) },
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
                      stringResource(R.string.apps_loading)
                    } else {
                      stringResource(R.string.apps_not_found)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              item {
                Text(
                  stringResource(R.string.bypass_app_note),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            if (!trustTunnelActive) {
              item { SettingsSectionTitle(stringResource(R.string.dpi_protection)) }
              item {
                SettingChoice(
                  title = stringResource(R.string.standard),
                  subtitle = stringResource(R.string.standard_description),
                  selected = dpi == ProfileSelection.DPI_OFF,
                  onClick = { dpi = ProfileSelection.DPI_OFF },
                )
              }
              item {
                SettingChoice(
                  title = stringResource(R.string.tls_fragmentation),
                  subtitle = stringResource(R.string.tls_fragmentation_description),
                  selected = dpi == ProfileSelection.DPI_TLS_FRAGMENT,
                  onClick = { dpi = ProfileSelection.DPI_TLS_FRAGMENT },
                )
              }
            }
          }
          HorizontalDivider()
          DialogActions(
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = onDismiss,
            confirmLabel = stringResource(R.string.action_apply),
            confirmDescription = stringResource(R.string.apply_routing),
            confirmIcon = Icons.Rounded.Check,
            confirmEnabled = canApply,
            onConfirm = { onApply(route, direct, vpn, appMode, dpi, packages) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
          )
        }
      }
  }
}

private val StringSetSaver = Saver<Set<String>, ArrayList<String>>(
  save = { ArrayList(it) },
  restore = { it.toSet() },
)

/**
 * Full-window dialog frame: pads for system bars, cutouts and the IME, and
 * dismisses when the scrim around the content is tapped (a full-size dialog
 * window never receives the platform's "outside" touch).
 */
@Composable
private fun FullScreenDialog(
  onDismiss: () -> Unit,
  content: @Composable BoxScope.() -> Unit,
) {
  val currentOnDismiss by rememberUpdatedState(onDismiss)
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) { detectTapGestures { currentOnDismiss() } }
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .imePadding()
        .padding(12.dp),
      contentAlignment = Alignment.Center,
      content = content,
    )
  }
}

/** Swallows taps on dialog content so they never reach the dismissing scrim. */
private fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/** Labelled dismiss/confirm buttons for dialogs (no icon-only actions). */
@Composable
private fun DialogActions(
  dismissLabel: String,
  onDismiss: () -> Unit,
  confirmLabel: String,
  confirmIcon: ImageVector,
  confirmEnabled: Boolean,
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier,
  confirmDescription: String = confirmLabel,
  dismissEnabled: Boolean = true,
  loading: Boolean = false,
) {
  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    TextButton(
      onClick = onDismiss,
      enabled = dismissEnabled,
      shapes = ButtonDefaults.shapes(),
    ) {
      Text(dismissLabel)
    }
    Button(
      onClick = onConfirm,
      enabled = confirmEnabled,
      shapes = ButtonDefaults.shapes(),
      modifier = Modifier.semantics { contentDescription = confirmDescription },
    ) {
      if (loading) {
        LoadingIndicator(
          modifier = Modifier.size(ButtonDefaults.IconSize + 6.dp),
          color = LocalContentColor.current,
        )
      } else {
        Icon(confirmIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
      }
      Spacer(Modifier.size(ButtonDefaults.IconSpacing))
      Text(confirmLabel)
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
  val (container, shape) = animatedSelection(
    selected = selected,
    selectedColor = MaterialTheme.colorScheme.secondaryContainer,
    unselectedColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    label = "routing",
  )
  Surface(
    selected = selected,
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .semantics { role = Role.RadioButton },
    shape = shape,
    color = container,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SelectionIndicator(selected)
      Column(Modifier.weight(1f)) {
        Text(
          title,
          style = if (selected) {
            MaterialTheme.typography.bodyMediumEmphasized
          } else {
            MaterialTheme.typography.bodyMedium
          },
        )
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

/**
 * Container colour and corner shape for a selectable row, animated with the
 * theme's expressive motion scheme (fast effects for colour, fast spatial
 * spring for the corner morph).
 */
@Composable
private fun animatedSelection(
  selected: Boolean,
  selectedColor: Color,
  unselectedColor: Color,
  label: String,
): Pair<Color, Shape> {
  val motion = MaterialTheme.motionScheme
  val color by animateColorAsState(
    targetValue = if (selected) selectedColor else unselectedColor,
    animationSpec = motion.fastEffectsSpec(),
    label = "$label selection color",
  )
  val corner by animateDpAsState(
    targetValue = if (selected) 28.dp else 16.dp,
    animationSpec = motion.fastSpatialSpec(),
    label = "$label selection shape",
  )
  return color to RoundedCornerShape(corner)
}

@Composable
private fun SelectionIndicator(selected: Boolean) {
  RadioButton(
    selected = selected,
    onClick = null,
    modifier = Modifier.size(24.dp).clearAndSetSemantics {},
  )
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
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.errorContainer,
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = stringResource(R.string.connection_failed_title),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
      if (code != null) {
        Text(
          text = stringResource(R.string.error_code, code),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.72f),
        )
      }
      if (diagnosticReportAvailable) {
        OutlinedButton(
          onClick = onCopyDiagnostic,
          shapes = ButtonDefaults.shapes(),
          modifier = Modifier.align(Alignment.End),
          colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
          ),
        ) {
          Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
          )
          Spacer(Modifier.size(ButtonDefaults.IconSpacing))
          Text(stringResource(R.string.copy_diagnostics))
        }
      }
    }
  }
}

@Composable
private fun RoutingNoticeCard(message: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Text(
      text = message,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ImportActions(
  importing: Boolean,
  onPaste: () -> Unit,
  onScanQr: () -> Unit,
  onImportFile: () -> Unit,
) {
  // Labelled source buttons; the full phrase stays available to TalkBack.
  val actions = listOf(
    Triple(Icons.Rounded.ContentPaste, R.string.action_paste, R.string.paste_from_clipboard) to onPaste,
    Triple(Icons.Rounded.QrCodeScanner, R.string.action_scan_qr, R.string.scan_qr) to onScanQr,
    Triple(Icons.Rounded.Description, R.string.action_choose_file, R.string.choose_singbox_json) to onImportFile,
  )
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    actions.forEach { (labels, action) ->
      val (icon, label, description) = labels
      val descriptionText = stringResource(description)
      FilledTonalButton(
        onClick = action,
        enabled = !importing,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.semantics { contentDescription = descriptionText },
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight, hasStartIcon = true),
      ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(label))
      }
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
  FullScreenDialog(onDismiss = { if (!importing) onDismiss() }) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 560.dp)
          .heightIn(max = 640.dp)
          .consumeTaps(),
        shape = MaterialTheme.shapes.extraLarge,
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
              shape = MaterialShapes.Cookie4Sided.toShape(),
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
              stringResource(R.string.add_subscription),
              modifier = Modifier.weight(1f).semantics { heading() },
              style = MaterialTheme.typography.headlineSmallEmphasized,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Text(
            text = stringResource(R.string.import_formats),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
          )
          OutlinedTextField(
            value = subscriptionUrl,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.subscription_or_profile_link)) },
            minLines = 1,
            maxLines = 3,
            enabled = !importing,
            isError = error != null,
            supportingText = error?.let { message ->
              {
                Text(
                  text = message,
                  modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                  color = MaterialTheme.colorScheme.error,
                )
              }
            },
            shape = MaterialTheme.shapes.medium,
          )
          ImportActions(
            importing = importing,
            onPaste = onPaste,
            onScanQr = onScanQr,
            onImportFile = onImportFile,
          )
          HorizontalDivider()
          DialogActions(
            dismissLabel = stringResource(R.string.cancel),
            dismissEnabled = !importing,
            onDismiss = onDismiss,
            confirmLabel = stringResource(R.string.action_import),
            confirmDescription = stringResource(
              if (importing) R.string.profile_importing else R.string.profile_import,
            ),
            confirmIcon = Icons.Rounded.FileDownload,
            confirmEnabled = subscriptionUrl.contains("://") && !importing,
            loading = importing,
            onConfirm = onImportUrl,
          )
        }
      }
  }
}

@Composable
private fun VeilarkMark(
  color: Color,
  modifier: Modifier = Modifier,
) {
  Icon(
    painter = painterResource(R.drawable.veilark_mark),
    contentDescription = null,
    tint = color,
    modifier = modifier,
  )
}

@Preview(name = "Disconnected · light", showBackground = true, widthDp = 393, heightDp = 900)
@Preview(name = "Compact · large text", showBackground = true, widthDp = 320, heightDp = 900, fontScale = 1.5f)
@Preview(name = "Landscape · light", showBackground = true, widthDp = 800, heightDp = 360)
@Preview(name = "Tablet · light", showBackground = true, widthDp = 840, heightDp = 900)
@Composable
private fun MainScreenPreview() {
  VeilarkTheme(dynamicColor = false) {
    MainScreen(
      profileName = "Personal subscription",
      singBoxAvailable = true,
      trustTunnelAvailable = true,
    )
  }
}

@Preview(name = "Connected · dark", showBackground = true, widthDp = 393, heightDp = 900)
@Composable
private fun ConnectedMainScreenPreview() {
  VeilarkTheme(darkTheme = true) {
    MainScreen(
      profileName = "Personal subscription",
      connectionState = ConnectionState.Connected,
      trustTunnelActive = true,
      singBoxAvailable = true,
      trustTunnelAvailable = true,
      selectedNodeTag = "preview-nl",
      connectionNodes = listOf(ConnectionNode("preview-nl", "Netherlands", "TrustTunnel")),
      nodeLatencies = mapOf("preview-nl" to 42),
    )
  }
}

@Preview(name = "Connecting · light", showBackground = true, widthDp = 393, heightDp = 900)
@Composable
private fun ConnectingMainScreenPreview() {
  VeilarkTheme {
    MainScreen(profileName = "Personal subscription", connectionState = ConnectionState.Connecting)
  }
}

@Preview(name = "Failed · dark · large text", showBackground = true, widthDp = 360, heightDp = 900, fontScale = 1.3f)
@Composable
private fun FailedMainScreenPreview() {
  VeilarkTheme(darkTheme = true) {
    MainScreen(profileName = "Personal subscription", connectionState = ConnectionState.Failed)
  }
}

@Preview(name = "Importing · update download", showBackground = true, widthDp = 393, heightDp = 1100)
@Composable
private fun ImportingAndUpdatingPreview() {
  VeilarkTheme(dynamicColor = false) {
    MainScreen(
      profileName = "Personal subscription",
      importing = true,
      updateStatus = "Veilark 1.0 is available",
      updateAvailable = true,
      updating = true,
      updateProgress = 0.42f,
    )
  }
}

@Preview(name = "Technical log · duplicate entries", showBackground = true, widthDp = 393, heightDp = 700)
@Composable
private fun TechnicalLogPreview() {
  val duplicate = TechnicalLogEntry(1_700_000_000_000L, "WARN", "vpn", "Retrying handshake")
  VeilarkTheme(darkTheme = true) {
    TechnicalLogScreen(
      entries = listOf(
        TechnicalLogEntry(1_699_999_999_000L, "INFO", "core", "Tunnel started"),
        duplicate,
        duplicate,
        TechnicalLogEntry(1_700_000_001_000L, "ERROR", "dns", "Resolver timeout"),
      ),
      onBack = {},
      onClear = {},
      onRunDiagnostics = {},
    )
  }
}

@Preview(name = "About · large flexible app bar", showBackground = true, widthDp = 393, heightDp = 800)
@Composable
private fun AboutScreenPreview() {
  VeilarkTheme(dynamicColor = false) {
    AboutScreen(onBack = {}, onOpenDocument = {}, onOpenSubscriptionAccount = {}, onOpenWebAccount = {})
  }
}
