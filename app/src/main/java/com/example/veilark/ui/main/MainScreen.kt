@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.example.veilark.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
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
import com.example.veilark.profile.CoreProfileKind
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
  connectionError: String? = null,
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
  killSwitchEnabled: Boolean = true,
  selectedApplications: Set<String> = emptySet(),
  installedApplications: List<InstalledApp> = emptyList(),
  geoUpdating: Boolean = false,
  geoUpdateMessage: String? = null,
  geoStatusDescription: String? = null,
  coreBetaActive: Boolean = false,
  coreBetaAvailable: Boolean = false,
  coreBetaProfileName: String? = null,
  coreBetaRuntimeAvailable: Boolean = false,
  stopAvailable: Boolean = false,
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
  onSelectEngine: (String) -> Unit = { onSwitchProfile() },
  coreRouteChoice: CoreRouteChoice? = null,
  onSelectCoreRoute: (relay: Boolean) -> Unit = {},
  killSwitchBlocking: Boolean = false,
  onRefreshLatency: () -> Unit = {},
  onOpenRouting: () -> Unit = {},
  onRefreshGeo: () -> Unit = {},
  onApplyRouting: (String, String, String, String, String, Set<String>) -> Boolean =
    { _, _, _, _, _, _ -> true },
  onKillSwitchChange: (Boolean) -> Unit = {},
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
  var consumedNoticeRequest by rememberSaveable { mutableLongStateOf(0L) }
  LaunchedEffect(updateNoticeRequest) {
    if (updateNoticeRequest > 0 && selfUpdateEnabled) onCheckUpdate()
  }
  var showNodes by rememberSaveable { mutableStateOf(false) }
  var showRouting by rememberSaveable { mutableStateOf(false) }
  var showTechnicalLogs by rememberSaveable { mutableStateOf(false) }
  var showAbout by rememberSaveable { mutableStateOf(false) }
  // After a manual check the update card stays up to show the result, even
  // when there is nothing to install.
  var updateCheckRequested by rememberSaveable { mutableStateOf(false) }
  var legalDocument by rememberSaveable { mutableStateOf<LegalDocument?>(null) }
  // Import text may contain a Core PSK. Never serialize it into SavedState/Bundle.
  var subscriptionUrl by remember { mutableStateOf("") }
  var importAttempted by rememberSaveable { mutableStateOf(false) }
  var importSubmitted by remember { mutableStateOf(false) }
  var observedImporting by remember { mutableStateOf(false) }
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
    coreBetaActive,
    coreBetaProfileName,
    trustTunnelActive,
    trustSubscriptions,
    singBoxSubscriptions,
  ) {
    if (coreBetaActive) {
      listOfNotNull(coreBetaProfileName?.let { SubscriptionUiItem(id = "core-beta", name = it, nodeCount = 1, refreshable = false, deletable = true) })
    } else if (trustTunnelActive) {
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
        subscriptionUrl = ""
      }
      importSubmitted = false
      observedImporting = false
    }
  }

  // A validated deep link uses the same import callback as the reviewed manual
  // surface. LaunchedEffect makes this one-shot for each request value; clearing
  // the request immediately prevents recomposition from importing it twice.
  // Any web page can open a veilark://import link, and an import replaces the
  // active profile. The link therefore only pre-fills the import dialog; the
  // user still reviews the source and confirms it.
  LaunchedEffect(externalImportUrl) {
    externalImportUrl?.let { value ->
      subscriptionUrl = value
      importAttempted = false
      showImport = true
      onExternalImportConsumed()
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
        engineDescription = engineDescription,
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
        subscriptionUrl = ""
      },
      onImportFile = {
        showImport = false
        subscriptionUrl = ""
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
      killSwitchEnabled = killSwitchEnabled,
      selectedApplications = selectedApplications,
      installedApplications = installedApplications,
      trustTunnelActive = trustTunnelActive,
      coreBetaActive = coreBetaActive,
      geoRefreshEnabled = connectionState == ConnectionState.Disconnected ||
        connectionState == ConnectionState.Failed,
      geoUpdating = geoUpdating,
      geoUpdateMessage = geoUpdateMessage,
      geoStatusDescription = geoStatusDescription,
      onRefreshGeo = onRefreshGeo,
      onKillSwitchChange = onKillSwitchChange,
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

  val reconnectRequired = connectionState != ConnectionState.Disconnected &&
    connectionState != ConnectionState.Failed
  val effectiveDpiMode = if (coreBetaActive) ProfileSelection.DPI_OFF else dpiMode
  // The routing switches on the main screen save through the same callback as
  // the routing dialog, so both places always agree on the stored policy.
  val setRussiaDirect: (Boolean) -> Unit = { enabled ->
    val route = if (enabled) ProfileSelection.ROUTING_RU_DIRECT else ProfileSelection.ROUTING_ALL
    if (onApplyRouting(route, directRoutes, vpnRoutes, applicationMode, effectiveDpiMode, selectedApplications)) {
      coroutineScope.launch {
        snackbarHostState.showSnackbar(
          if (reconnectRequired) routingSavedReconnectMessage else routingSavedMessage,
        )
      }
    }
  }
  val openRouting = {
    onOpenRouting()
    showRouting = true
  }
  val emblemAction = if (!stopAvailable && profileName == null &&
    connectionState != ConnectionState.Connected && connectionState != ConnectionState.Connecting
  ) {
    openImport
  } else {
    onConnect
  }
  val currentNodeTag = if (selectedNodeTag == ProfileSelection.AUTOMATIC_TAG) automaticNodeTag else selectedNodeTag
  val currentNodeName = connectionNodes.firstOrNull { it.tag == currentNodeTag }?.name

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
          MainOverflowMenu(
            selfUpdateEnabled = selfUpdateEnabled,
            updating = updating,
            onOpenTechnicalLog = { showTechnicalLogs = true },
            onOpenAbout = { showAbout = true },
            onCheckUpdate = {
              updateCheckRequested = true
              onCheckUpdate()
            },
          )
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
        ),
      )
    },
  ) { innerPadding ->
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      // One screen, no scrolling on a phone: state and the main action in the
      // middle, the connection and protection controls under the thumb. Small
      // windows and large font scales fall back to scrolling.
      Column(
        modifier = Modifier
          .testTag("main_content_list")
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          // Applied inside the scroll so the content fills the screen and the
          // spacing below can push the controls to the bottom.
          .heightIn(min = maxHeight)
          .wrapContentWidth(Alignment.CenterHorizontally)
          .widthIn(max = 560.dp)
          .padding(horizontal = 16.dp)
          .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          if (selfUpdateEnabled && (updateAvailable || updating || updateCheckRequested)) {
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
          if (importError != null) {
            ErrorCard(
              title = stringResource(R.string.attention_title),
              message = importError,
              code = null,
              diagnosticReportAvailable = false,
              onCopyDiagnostic = onCopyDiagnostic,
            )
          }
        }

        ConnectionStatus(
          profileName = profileName,
          state = connectionState,
          stage = startupStage,
          nodeName = currentNodeName,
          latency = nodeLatencies[currentNodeTag],
          importing = importing,
          authenticatedOnly = coreBetaActive,
          stopAvailable = stopAvailable,
          connectionError = connectionError,
          failureCode = failureCode,
          diagnosticReportAvailable = diagnosticReportAvailable,
          onEmblemClick = emblemAction,
          onRetry = onConnect,
          onCopyDiagnostic = onCopyDiagnostic,
          onPaste = pasteAndReview,
          onScanQr = scanAndImport,
          onImportFile = onImportFile,
          onEnterLink = openImport,
          onOpenSubscriptionAccount = openSubscriptionAccount,
          modifier = Modifier.padding(vertical = 24.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          // Shown as soon as any engine has a profile, so switching to it
          // stays possible while the current engine has none.
          if (profileName != null || activeSubscriptions.isNotEmpty() ||
            singBoxAvailable || trustTunnelAvailable || coreBetaAvailable
          ) {
            ProfileCard(
              profileName = profileName,
              coreBetaActive = coreBetaActive,
              trustTunnelActive = trustTunnelActive,
              nodes = connectionNodes,
              currentNodeName = currentNodeName,
              subscriptions = activeSubscriptions,
              selectedSubscriptionId = if (coreBetaActive) {
                "core-beta"
              } else if (trustTunnelActive) {
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
              canRefreshLatency = !coreBetaActive && (trustTunnelActive ||
                profileName != null),
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
              engineSelector = {
                EngineSelectorCard(
                  coreBetaActive = coreBetaActive,
                  coreBetaAvailable = coreBetaAvailable,
                  coreBetaRuntimeAvailable = coreBetaRuntimeAvailable,
                  trustTunnelActive = trustTunnelActive,
                  singBoxAvailable = singBoxAvailable,
                  trustTunnelAvailable = trustTunnelAvailable,
                  connectionState = connectionState,
                  onSelectEngine = onSelectEngine,
                )
              },
            )
          }
          ProtectionCard(
            routingMode = routingMode,
            applicationMode = applicationMode,
            selectedApplicationCount = selectedApplications.size,
            coreBetaActive = coreBetaActive,
            killSwitchEnabled = killSwitchEnabled,
            onRussiaDirectChange = setRussiaDirect,
            onKillSwitchChange = onKillSwitchChange,
            onOpenRouting = openRouting,
          )
          if (routingNotice != null) {
            RoutingNoticeCard(message = routingNotice)
          }
        }
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

/**
 * The centre of the main screen: the connection emblem, which is also the
 * connect / disconnect button, and the state under it. Failures, connection
 * progress and the first-run import choices all live here, next to the action
 * they belong to.
 */
@Composable
private fun ConnectionStatus(
  profileName: String?,
  state: ConnectionState,
  stage: StartupStage,
  nodeName: String?,
  latency: Int?,
  importing: Boolean,
  authenticatedOnly: Boolean,
  stopAvailable: Boolean,
  connectionError: String?,
  failureCode: String?,
  diagnosticReportAvailable: Boolean,
  onEmblemClick: () -> Unit,
  onRetry: () -> Unit,
  onCopyDiagnostic: () -> Unit,
  onPaste: () -> Unit,
  onScanQr: () -> Unit,
  onImportFile: () -> Unit,
  onEnterLink: () -> Unit,
  onOpenSubscriptionAccount: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val connected = state == ConnectionState.Connected
  val connecting = state == ConnectionState.Connecting
  val failed = state == ConnectionState.Failed || (state == ConnectionState.Disconnected && connectionError != null)
  val action = connectionCardAction(state, profileName != null, importing, stopAvailable)
  val motion = MaterialTheme.motionScheme
  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    ConnectionEmblem(
      state = state,
      failed = failed,
      action = action,
      onClick = onEmblemClick,
    )
    Spacer(Modifier.height(24.dp))
    AnimatedContent(
      targetState = when {
        profileName == null && !connected && !connecting -> null
        failed -> ConnectionState.Failed
        else -> state
      },
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
          null -> stringResource(R.string.add_subscription)
          ConnectionState.Disconnected -> stringResource(R.string.vpn_off)
          ConnectionState.Connecting -> stringResource(R.string.connecting_title)
          ConnectionState.Connected -> stringResource(if (authenticatedOnly) R.string.core_authenticated_title else R.string.vpn_protected)
          ConnectionState.Failed -> stringResource(R.string.vpn_connect_failed)
        },
        style = MaterialTheme.typography.headlineMediumEmphasized,
        textAlign = TextAlign.Center,
      )
    }
    Spacer(Modifier.height(6.dp))
    val subtitle = when {
      profileName == null && !connected && !connecting -> stringResource(R.string.import_hint_short)
      connecting -> stringResource(
        R.string.connection_step,
        stage.ordinal.coerceIn(1, StartupStageCount),
        StartupStageCount,
        stringResource(stage.titleRes),
      )
      failed -> connectionError ?: stringResource(R.string.vpn_connect_failed)
      connected && authenticatedOnly -> stringResource(R.string.core_authenticated_hint)
      connected -> listOfNotNull(
        nodeName ?: profileName,
        latency?.let { stringResource(R.string.latency_ms, it) },
      ).joinToString(" · ")
      importing -> stringResource(R.string.profile_checking)
      else -> stringResource(R.string.tap_to_connect)
    }
    Text(
      text = subtitle,
      modifier = Modifier.widthIn(max = 340.dp),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    when {
      connecting -> {
        Spacer(Modifier.height(16.dp))
        StageProgress(current = stage.ordinal.coerceIn(0, StartupStageCount))
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onEmblemClick, shapes = ButtonDefaults.shapes()) {
          Text(stringResource(R.string.connection_cancel))
        }
      }
      failed -> {
        if (failureCode != null) {
          Spacer(Modifier.height(4.dp))
          Text(
            text = stringResource(R.string.error_code, failureCode),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.retry))
          }
          if (diagnosticReportAvailable) {
            FilledTonalButton(onClick = onCopyDiagnostic, shapes = ButtonDefaults.shapes()) {
              Text(stringResource(R.string.copy_diagnostics))
            }
          }
        }
      }
      profileName == null && !connected -> {
        Spacer(Modifier.height(20.dp))
        FirstRunImportActions(
          enabled = !importing,
          onPaste = onPaste,
          onScanQr = onScanQr,
          onImportFile = onImportFile,
          onEnterLink = onEnterLink,
          onOpenSubscriptionAccount = onOpenSubscriptionAccount,
        )
      }
    }
  }
}

private val StartupStageCount = StartupStage.entries.size - 1

/** Five short segments, one per startup stage; done stages are filled. */
@Composable
private fun StageProgress(current: Int) {
  Row(
    modifier = Modifier.clearAndSetSemantics {},
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    repeat(StartupStageCount) { index ->
      val color by animateColorAsState(
        targetValue = if (index < current) {
          MaterialTheme.colorScheme.onSurface
        } else {
          MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "startup stage segment",
      )
      Box(
        Modifier
          .size(width = 36.dp, height = 6.dp)
          .background(color, CircleShape),
      )
    }
  }
}

/**
 * The connection emblem doubles as the main button. It morphs from a soft
 * square (off) into a 12-sided seal (protected), turns slowly only while
 * connecting and shrinks a little under the finger. Nothing loops at rest.
 */
@Composable
private fun ConnectionEmblem(
  state: ConnectionState,
  failed: Boolean,
  action: ConnectionCardAction,
  onClick: () -> Unit,
) {
  val connected = state == ConnectionState.Connected
  val connecting = state == ConnectionState.Connecting
  val motion = MaterialTheme.motionScheme
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed) 0.92f else 1f,
    animationSpec = motion.fastSpatialSpec(),
    label = "emblem press",
  )
  val emblemMorph = remember { Morph(emblemPolygon(false), emblemPolygon(true)) }
  val morphProgress by animateFloatAsState(
    targetValue = if (connected || connecting) 1f else 0f,
    animationSpec = motion.slowSpatialSpec(),
    label = "connection emblem morph",
  )
  val restRotation by animateFloatAsState(
    targetValue = if (connected) 45f else 0f,
    animationSpec = motion.slowSpatialSpec(),
    label = "connection emblem rotation",
  )
  val spin = if (connecting) {
    val transition = rememberInfiniteTransition(label = "connecting spin")
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(tween(durationMillis = 6_000, easing = LinearEasing)),
      label = "connecting rotation",
    ).value
  } else {
    0f
  }
  val container by animateColorAsState(
    targetValue = when {
      failed -> MaterialTheme.colorScheme.errorContainer
      connecting -> MaterialTheme.colorScheme.surfaceContainerHighest
      action == ConnectionCardAction.Add -> MaterialTheme.colorScheme.surfaceContainerHigh
      else -> MaterialTheme.colorScheme.primary
    },
    animationSpec = motion.defaultEffectsSpec(),
    label = "connection emblem color",
  )
  val content = when {
    failed -> MaterialTheme.colorScheme.onErrorContainer
    connecting || action == ConnectionCardAction.Add -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onPrimary
  }
  val description = when (action) {
    ConnectionCardAction.Cancel -> stringResource(R.string.connection_cancel)
    ConnectionCardAction.Disconnect -> stringResource(R.string.disconnect)
    ConnectionCardAction.Checking -> stringResource(R.string.profile_checking)
    ConnectionCardAction.Add -> stringResource(R.string.profile_add)
    ConnectionCardAction.Connect -> stringResource(R.string.connect)
  }
  Box(
    modifier = Modifier
      .size(176.dp)
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
      }
      .clickable(
        interactionSource = interaction,
        indication = null,
        enabled = action != ConnectionCardAction.Checking,
        role = Role.Button,
        onClickLabel = description,
        onClick = onClick,
      )
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier
        .matchParentSize()
        .graphicsLayer { rotationZ = restRotation + spin }
        .clip(MorphShape(emblemMorph, morphProgress))
        .background(container),
    )
    when {
      action == ConnectionCardAction.Checking -> LoadingIndicator(
        modifier = Modifier.size(72.dp),
        color = content,
      )
      failed -> Icon(
        Icons.Rounded.Refresh,
        contentDescription = null,
        tint = content,
        modifier = Modifier.size(64.dp),
      )
      action == ConnectionCardAction.Add -> Icon(
        Icons.Rounded.Add,
        contentDescription = null,
        tint = content,
        modifier = Modifier.size(64.dp),
      )
      connected || connecting -> VeilarkMark(color = content, modifier = Modifier.size(76.dp))
      else -> Icon(
        Icons.Rounded.PowerSettingsNew,
        contentDescription = null,
        tint = content,
        modifier = Modifier.size(64.dp),
      )
    }
  }
}

/** First run: the three ways to add a subscription, without opening a dialog first. */
@Composable
private fun FirstRunImportActions(
  enabled: Boolean,
  onPaste: () -> Unit,
  onScanQr: () -> Unit,
  onImportFile: () -> Unit,
  onEnterLink: () -> Unit,
  onOpenSubscriptionAccount: () -> Unit,
) {
  val height = ButtonDefaults.MediumContainerHeight
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Button(
      onClick = onPaste,
      enabled = enabled,
      shapes = ButtonDefaults.shapesFor(height),
      modifier = Modifier.fillMaxWidth().heightIn(min = height),
    ) {
      Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
      Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
      Text(stringResource(R.string.action_paste), style = ButtonDefaults.textStyleFor(height))
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilledTonalButton(
        onClick = onScanQr,
        enabled = enabled,
        shapes = ButtonDefaults.shapesFor(height),
        modifier = Modifier.weight(1f).heightIn(min = height),
      ) {
        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.action_scan_qr), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      FilledTonalButton(
        onClick = onImportFile,
        enabled = enabled,
        shapes = ButtonDefaults.shapesFor(height),
        modifier = Modifier.weight(1f).heightIn(min = height),
      ) {
        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.action_choose_file), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    TextButton(onClick = onEnterLink, enabled = enabled, shapes = ButtonDefaults.shapes()) {
      Text(stringResource(R.string.action_enter_link))
    }
    TextButton(onClick = onOpenSubscriptionAccount, shapes = ButtonDefaults.shapes()) {
      Text(stringResource(R.string.no_subscription_get_one))
    }
  }
}

internal enum class ConnectionCardAction { Cancel, Disconnect, Checking, Add, Connect }

/** A live engine lease takes precedence over import progress and missing captions. */
internal fun connectionCardAction(
  state: ConnectionState,
  hasProfile: Boolean,
  importing: Boolean,
  stopAvailable: Boolean = false,
): ConnectionCardAction = when {
  state == ConnectionState.Connected -> ConnectionCardAction.Disconnect
  stopAvailable || state == ConnectionState.Connecting -> ConnectionCardAction.Cancel
  importing -> ConnectionCardAction.Checking
  !hasProfile -> ConnectionCardAction.Add
  else -> ConnectionCardAction.Connect
}

@Composable
private fun EngineSelectorCard(
  coreBetaActive: Boolean,
  coreBetaAvailable: Boolean,
  coreBetaRuntimeAvailable: Boolean,
  trustTunnelActive: Boolean,
  singBoxAvailable: Boolean,
  trustTunnelAvailable: Boolean,
  connectionState: ConnectionState,
  onSelectEngine: (String) -> Unit,
) {
  val alternativeAvailable = if (trustTunnelActive) singBoxAvailable else trustTunnelAvailable
  val idle = connectionState.allowsProfileSwitch()
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
              selected = !trustTunnelActive && !coreBetaActive,
              enabled = idle && singBoxAvailable,
              shapes = shapes,
              modifier = mod,
              onClick = { if (idle && singBoxAvailable && (trustTunnelActive || coreBetaActive)) onSelectEngine("sing-box") },
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
              onClick = { if (!trustTunnelActive && idle && trustTunnelAvailable) onSelectEngine("trusttunnel") },
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
          if (!coreBetaActive || !idle || !alternativeAvailable) Text(
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
          ToggleButton(
            checked = coreBetaActive,
            onCheckedChange = { if (idle && coreBetaAvailable && !coreBetaActive) onSelectEngine("veilark-core-beta") },
            enabled = idle && coreBetaAvailable,
            modifier = Modifier.fillMaxWidth().testTag("engine_core_beta"),
          ) {
            Text(stringResource(R.string.core_beta_title))
          }
          Text(
            stringResource(if (!coreBetaRuntimeAvailable) R.string.core_beta_unavailable else if (!coreBetaAvailable) R.string.core_beta_add_profile else R.string.core_beta_scope),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp),
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
  engineDescription: String,
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
      item {
        Text(
          text = stringResource(R.string.core_footer, engineDescription),
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
  coreBetaActive: Boolean,
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
  currentNodeName: String?,
  engineSelector: @Composable () -> Unit,
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
    // The connection row: which server the next tap on the emblem uses, and
    // the single entry point to subscriptions, servers and engines.
    Surface(
      onClick = onToggleNodes,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("connection_row")
        .semantics {
          role = Role.Button
          stateDescription = expansionStateDescription
        },
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
      Row(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Surface(
          modifier = Modifier.size(48.dp),
          shape = MaterialShapes.Cookie4Sided.toShape(),
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (coreBetaActive) {
              VeilarkMark(
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp),
              )
            } else {
              EngineGlyph(
                trust = trustTunnelActive,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(25.dp),
              )
            }
          }
        }
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = when {
              profileName == null -> stringResource(R.string.profile_missing)
              coreBetaActive -> stringResource(R.string.core_beta_title)
              selectedTag == ProfileSelection.AUTOMATIC_TAG && currentNodeName != null ->
                stringResource(R.string.automatic_node_current, currentNodeName)
              selectedNode != null -> selectedNode.name
              nodes.isNotEmpty() -> pluralStringResource(
                R.plurals.automatic_node_summary,
                nodes.size,
                nodes.size,
              )
              else -> stringResource(R.string.imported_configuration)
            },
            style = MaterialTheme.typography.titleMediumEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = profileName ?: stringResource(R.string.import_subscription),
              modifier = Modifier.weight(1f, fill = false),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "· " + stringResource(
                if (coreBetaActive) R.string.engine_label_core
                else if (trustTunnelActive) R.string.engine_label_trust
                else R.string.engine_label_singbox,
              ),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
            )
          }
        }
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Text(
            text = stringResource(R.string.connection_change),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
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
        engineSelector = engineSelector,
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
  engineSelector: @Composable () -> Unit,
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
        // The engine is part of "what am I connecting through", so it is
        // chosen here instead of taking a whole card on the main screen.
        item(key = "engine") { engineSelector() }
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
private fun SectionHeading(text: String) {
  Text(
    text = text,
    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp).semantics { heading() },
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun MainOverflowMenu(
  selfUpdateEnabled: Boolean,
  updating: Boolean,
  onOpenTechnicalLog: () -> Unit,
  onOpenAbout: () -> Unit,
  onCheckUpdate: () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) {
      Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      shape = RoundedCornerShape(20.dp),
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      if (selfUpdateEnabled) {
        DropdownMenuItem(
          text = { Text(stringResource(R.string.check_updates)) },
          leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
          enabled = !updating,
          onClick = {
            expanded = false
            onCheckUpdate()
          },
        )
      }
      DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_technical_log)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null) },
        onClick = {
          expanded = false
          onOpenTechnicalLog()
        },
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_about)) },
        leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
        onClick = {
          expanded = false
          onOpenAbout()
        },
      )
    }
  }
}

private enum class GroupPosition { First, Middle, Last }

private fun GroupPosition.shape() = when (this) {
  GroupPosition.First -> RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
  GroupPosition.Middle -> RoundedCornerShape(8.dp)
  GroupPosition.Last -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
}

/**
 * The two switches people actually flip (Russia direct, kill switch) live on
 * the main screen; everything rarer is one tap away in routing settings.
 */
@Composable
private fun ProtectionCard(
  routingMode: String,
  applicationMode: String,
  selectedApplicationCount: Int,
  coreBetaActive: Boolean,
  killSwitchEnabled: Boolean,
  onRussiaDirectChange: (Boolean) -> Unit,
  onKillSwitchChange: (Boolean) -> Unit,
  onOpenRouting: () -> Unit,
) {
  val simpleRouting = routingMode == ProfileSelection.ROUTING_ALL ||
    routingMode == ProfileSelection.ROUTING_RU_DIRECT
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    if (simpleRouting) {
      SwitchRow(
        title = stringResource(R.string.russia_direct),
        description = stringResource(R.string.russia_direct_row_description),
        checked = routingMode == ProfileSelection.ROUTING_RU_DIRECT,
        onCheckedChange = onRussiaDirectChange,
        position = GroupPosition.First,
        modifier = Modifier.testTag("russia_direct_switch"),
      )
    }
    SwitchRow(
      title = stringResource(R.string.kill_switch),
      description = stringResource(R.string.kill_switch_row_description),
      checked = killSwitchEnabled,
      onCheckedChange = onKillSwitchChange,
      position = if (simpleRouting) GroupPosition.Middle else GroupPosition.First,
    )
    val summary = buildList {
      if (!simpleRouting) add(stringResource(R.string.custom_rules))
      add(
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
      if (coreBetaActive && routingMode == ProfileSelection.ROUTING_ALL) {
        add(stringResource(R.string.core_ipv4_mode))
      }
    }.joinToString(" · ")
    Surface(
      onClick = onOpenRouting,
      modifier = Modifier.fillMaxWidth().testTag("routing_card"),
      shape = GroupPosition.Last.shape(),
      color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
      Row(
        modifier = Modifier.heightIn(min = 64.dp).padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            stringResource(R.string.routing_and_apps),
            style = MaterialTheme.typography.bodyLargeEmphasized,
          )
          Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Icon(
          Icons.AutoMirrored.Rounded.KeyboardArrowRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun SwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  position: GroupPosition,
  modifier: Modifier = Modifier,
) {
  // The whole row toggles, so the target is the row and not the 52 dp switch.
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    shape = position.shape(),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Row(
      modifier = Modifier.heightIn(min = 64.dp).padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLargeEmphasized)
        Text(
          description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(checked = checked, onCheckedChange = null)
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
  killSwitchEnabled: Boolean,
  selectedApplications: Set<String>,
  installedApplications: List<InstalledApp>,
  trustTunnelActive: Boolean,
  coreBetaActive: Boolean,
  geoRefreshEnabled: Boolean,
  geoUpdating: Boolean,
  geoUpdateMessage: String?,
  geoStatusDescription: String?,
  onRefreshGeo: () -> Unit,
  onKillSwitchChange: (Boolean) -> Unit,
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
  var dpi by rememberSaveable { mutableStateOf(if (coreBetaActive) ProfileSelection.DPI_OFF else dpiMode) }
  var packages by rememberSaveable(stateSaver = StringSetSaver) {
    mutableStateOf(selectedApplications)
  }
  var search by rememberSaveable { mutableStateOf("") }
  var showCustomGeo by rememberSaveable { mutableStateOf(false) }
  if (showCustomGeo) {
    CustomGeoProfileDialog(
      initialDirect = direct,
      initialVpn = vpn,
      geoUpdating = geoUpdating,
      geoStatusDescription = geoStatusDescription,
      geoUpdateMessage = geoUpdateMessage,
      refreshEnabled = geoRefreshEnabled,
      trustTunnelActive = trustTunnelActive,
      onRefreshGeo = onRefreshGeo,
      onUse = { profile ->
        route = ProfileSelection.ROUTING_MANUAL
        direct = profile.directEntries
        vpn = profile.vpnEntries
        showCustomGeo = false
      },
      onDismiss = { showCustomGeo = false },
    )
    return
  }
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
                when {
                  coreBetaActive -> stringResource(R.string.core_beta_title)
                  trustTunnelActive -> "TrustTunnel"
                  else -> "sing-box"
                },
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
            if (!coreBetaActive) {
              item { SettingsSectionTitle(stringResource(R.string.traffic)) }
              geoStatusDescription?.let { description ->
                item { Text(description, style = MaterialTheme.typography.bodySmall) }
              }
              item {
                OutlinedButton(onClick = { showCustomGeo = true }, modifier = Modifier.fillMaxWidth()) {
                  Text(stringResource(R.string.geo_scenario_title))
                }
              }
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
                  Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      Text(stringResource(R.string.geo_scenario_selection_summary,
                        direct.lineSequence().count { it.isNotBlank() },
                        vpn.lineSequence().count { it.isNotBlank() }), style = MaterialTheme.typography.bodyMedium)
                      Text(stringResource(R.string.geo_scenario_remaining), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                      OutlinedButton(onClick = { showCustomGeo = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.geo_scenario_edit))
                      }
                    }
                  }
                }
              }
            } else {
              item { Text(stringResource(R.string.core_beta_scope), style = MaterialTheme.typography.bodyMedium) }
              item {
                SettingChoice(title = stringResource(R.string.all_traffic_vpn),
                  selected = route == ProfileSelection.ROUTING_ALL,
                  onClick = { route = ProfileSelection.ROUTING_ALL })
              }
              item {
                SettingChoice(title = stringResource(R.string.russia_direct),
                  subtitle = stringResource(R.string.fairway_ru_partial_description),
                  selected = route == ProfileSelection.ROUTING_RU_DIRECT,
                  onClick = { route = ProfileSelection.ROUTING_RU_DIRECT })
              }
              item {
                SettingChoice(title = stringResource(R.string.core_l3_manual),
                  subtitle = stringResource(R.string.core_l3_cidr_only),
                  selected = route == ProfileSelection.ROUTING_MANUAL,
                  onClick = { route = ProfileSelection.ROUTING_MANUAL })
              }
              if (route == ProfileSelection.ROUTING_MANUAL) {
                item {
                  OutlinedTextField(value = direct, onValueChange = { if (it.length <= 65_536) direct = it },
                    label = { Text(stringResource(R.string.core_l3_direct_cidrs)) },
                    supportingText = { Text(stringResource(R.string.core_l3_cidr_hint)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
                }
                item {
                  OutlinedTextField(value = vpn, onValueChange = { if (it.length <= 65_536) vpn = it },
                    label = { Text(stringResource(R.string.core_l3_vpn_cidrs)) },
                    supportingText = { Text(stringResource(R.string.core_l3_vpn_priority)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
                }
              }
            }
            if (trustTunnelActive || coreBetaActive) {
              item {
                Surface(
                  shape = RoundedCornerShape(18.dp),
                  color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                  Text(
                    text = stringResource(if (coreBetaActive) R.string.core_application_rules_note else R.string.trust_app_rules_note),
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
            if (!trustTunnelActive && !coreBetaActive) {
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
            item { SettingsSectionTitle(stringResource(R.string.kill_switch)) }
            item { KillSwitchToggle(enabled = killSwitchEnabled, onEnabledChange = onKillSwitchChange) }
            item {
              Text(
                stringResource(R.string.system_vpn_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            item {
              val context = LocalContext.current
              OutlinedButton(
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(stringResource(R.string.system_vpn_settings))
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

/** Applied immediately: the kill switch is app policy and needs no reconnect. */
@Composable
private fun KillSwitchToggle(
  enabled: Boolean,
  onEnabledChange: (Boolean) -> Unit,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        stringResource(R.string.kill_switch_description),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
      )
      Switch(checked = enabled, onCheckedChange = null)
    }
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
private fun ErrorCard(
  title: String,
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
        text = title,
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
            // Import material may be raw JSON, protocol credentials or a bearer URL.
            // Mask every family rather than trying to guess where its secrets begin.
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
              keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
            ),
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
            confirmEnabled = !importing && remember(subscriptionUrl) {
              subscriptionUrl.contains("://") || runCatching {
                CoreProfileKind.classifyImport(subscriptionUrl.toByteArray(Charsets.UTF_8)) != null
              }.getOrDefault(false)
            },
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
    AboutScreen(engineDescription = "sing-box 1.13.21", onBack = {}, onOpenDocument = {}, onOpenSubscriptionAccount = {}, onOpenWebAccount = {})
  }
}
