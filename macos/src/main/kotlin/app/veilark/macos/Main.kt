package app.veilark.macos

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.veilark.engine.TunnelEngineKind
import com.example.veilark.engine.TunnelStatus
import com.example.veilark.profile.ImportDeepLink
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.profile.SingBoxCatalogEntry
import com.example.veilark.session.LogEntry
import com.example.veilark.session.RuntimeMessages
import com.example.veilark.session.VeilarkSession
import com.example.veilark.theme.VeilarkTheme
import com.example.veilark.ui.MacClipboard
import com.example.veilark.ui.Strings
import com.example.veilark.update.MacUpdate
import com.example.veilark.update.MacUpdateClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.awt.desktop.AppReopenedListener
import java.awt.image.BufferedImage
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private enum class MacSection(
  val icon: ImageVector,
  val label: String,
) {
  OVERVIEW(Icons.Outlined.Home, Strings.overview),
  PROFILES(Icons.Outlined.Wifi, Strings.profiles),
  ROUTING(Icons.Outlined.Route, Strings.routing),
  DIAGNOSTICS(Icons.Outlined.NetworkCheck, Strings.diagnostics),
  SETTINGS(Icons.Outlined.Settings, Strings.settings),
}

/**
 * Validated `veilark://import?url=...` payloads delivered by macOS through Launch
 * Services. Registered before the Compose tree exists so that a cold start opened from a
 * link is not lost; AWT queues the open-URI event until a handler is installed.
 */
private val externalImportRequests = MutableStateFlow<String?>(null)
private val reopenRequests = MutableStateFlow(0L)
private val shutdownSession = AtomicReference<VeilarkSession?>(null)
private val shutdownCleanupStarted = AtomicBoolean(false)

private fun installShutdownCleanup() {
  Runtime.getRuntime().addShutdownHook(Thread({
    val session = shutdownSession.get() ?: return@Thread
    if (!shutdownCleanupStarted.compareAndSet(false, true)) return@Thread
    runCatching {
      runBlocking {
        withTimeoutOrNull(20_000) { session.stopForQuit() }
      }
    }
  }, "veilark-macos-shutdown"))
}

private fun installImportDeepLinkHandler() {
  if (!Desktop.isDesktopSupported()) return
  val desktop = Desktop.getDesktop()
  desktop.addAppEventListener(AppReopenedListener { reopenRequests.value += 1 })
  if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) return
  desktop.setOpenURIHandler { event ->
    // The inner URL is a bearer credential; unsupported links are dropped silently.
    ImportDeepLink.parse(event.uri)?.let { externalImportRequests.value = it }
  }
}

fun main() {
  // CTrayIcon reads this once. Set it before any AWT/Compose initialization, also for IDE runs.
  System.setProperty("apple.awt.enableTemplateImages", "true")
  StartupDiagnostics.install()
  runCatching(::installShutdownCleanup)
  runCatching(::installImportDeepLinkHandler)
  application {
  var startupAttempt by remember { mutableStateOf(0) }
  var startupFailed by remember { mutableStateOf(false) }
  val loadedSession by produceState<VeilarkSession?>(null, startupAttempt) {
    startupFailed = false
    try {
      value = withContext(Dispatchers.IO) {
        VeilarkSession.createDefault { StartupDiagnostics.record(it, "keychain") }
      }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      withContext(Dispatchers.IO) { StartupDiagnostics.record(failure, "session-startup") }
      startupFailed = true
    }
  }
  if (loadedSession == null) {
    Window(onCloseRequest = ::exitApplication, title = Strings.appName,
      state = rememberWindowState(width = 420.dp, height = 220.dp)) {
      VeilarkTheme {
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            if (!startupFailed) CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            val russian = java.util.Locale.getDefault().language == "ru"
            Text(if (startupFailed) {
              if (russian) "Не удалось открыть Veilark. Профили не изменены." else "Unable to open Veilark. Profiles were not changed."
            } else if (russian) "Открываем Veilark…" else "Opening Veilark…")
            if (startupFailed) TextButton(onClick = { startupAttempt += 1 }) {
              Text(if (russian) "Повторить" else "Retry")
            }
          }
        }
      }
    }
    return@application
  }
  val session = loadedSession!!
  SideEffect { shutdownSession.set(session) }
  val scope = rememberCoroutineScope()
  var selectedSection by remember { mutableStateOf(MacSection.OVERVIEW) }
  var tick by remember { mutableStateOf(0) }
  var windowVisible by remember { mutableStateOf(true) }
  var startupUpdate by remember { mutableStateOf<MacUpdate?>(null) }
  var startupUpdateError by remember { mutableStateOf<String?>(null) }
  val darkTheme = isSystemInDarkTheme()
  val trayIcon = remember { MenuBarIcon() }
  val windowIcon = remember(darkTheme) { BitmapPainter(brandBitmap(256, darkTheme).toComposeImageBitmap()) }
  val visualPreferences by produceState(VisualPreferences()) {
    value = withContext(Dispatchers.IO) { readVisualPreferences() }
  }
  val externalImportUrl by externalImportRequests.collectAsState()
  val reopenRequest by reopenRequests.collectAsState()

  LaunchedEffect(reopenRequest) {
    if (reopenRequest > 0) windowVisible = true
  }

  LaunchedEffect(externalImportUrl) {
    if (externalImportUrl != null) {
      selectedSection = MacSection.PROFILES
      windowVisible = true
    }
  }

  LaunchedEffect(Unit) {
    if (MacUpdateClient.configured) {
      runCatching { MacUpdateClient.check() }
        .onSuccess { startupUpdate = it }
        .onFailure { startupUpdateError = it.message ?: Strings.updateCheckFailed }
    }
  }

  LaunchedEffect(session) {
    while (true) {
      delay(when {
        session.recoveryPending -> 2_000
        session.status == TunnelStatus.CONNECTED -> 30_000
        else -> 60_000
      })
      session.reconcileStatus()
      session.reconcileDefaultRouteHandover()
    }
  }

  fun refresh() {
    tick += 1
  }

  fun toggleConnection() {
    scope.launch {
      if (session.status.isStopAction) {
        session.disconnect()
      } else {
        session.connect()
      }
      refresh()
    }
  }

  Tray(
    icon = trayIcon,
    tooltip = Strings.appName,
    onAction = { windowVisible = true },
    menu = {
      Item(Strings.appName, onClick = { windowVisible = true })
      Item(
        when (session.status) {
          TunnelStatus.CONNECTED, TunnelStatus.DEGRADED -> Strings.disconnect
          TunnelStatus.CONNECTING, TunnelStatus.RECONNECTING -> Strings.cancelConnection
          else -> Strings.connect
        },
        enabled = !session.busy || session.status == TunnelStatus.CONNECTING ||
          session.status == TunnelStatus.RECONNECTING,
        onClick = ::toggleConnection,
      )
      Separator()
      Item(Strings.profiles, onClick = {
        selectedSection = MacSection.PROFILES
        windowVisible = true
      })
      Item(Strings.diagnostics, onClick = {
        selectedSection = MacSection.DIAGNOSTICS
        windowVisible = true
      })
      Separator()
      Item(Strings.quit, onClick = {
        scope.launch {
          session.stopForQuit()
            .onSuccess { exitApplication() }
            .onFailure {
              selectedSection = MacSection.DIAGNOSTICS
              windowVisible = true
            }
        }
      })
    },
  )

  if (windowVisible) {
    Window(
      onCloseRequest = { windowVisible = false },
      title = Strings.appName,
      icon = windowIcon,
      state = rememberWindowState(width = 1_040.dp, height = 720.dp),
    ) {
      LaunchedEffect(window, reopenRequest, externalImportUrl) {
        window.minimumSize = Dimension(900, 620)
        window.extendedState = Frame.NORMAL
        window.toFront()
        window.requestFocus()
      }
      VeilarkTheme {
        CompositionLocalProvider(LocalVisualPreferences provides visualPreferences) {
        MacShell(
          session = session,
          selectedSection = selectedSection,
          onSectionSelected = { selectedSection = it },
          tick = tick,
          refresh = ::refresh,
          onToggleConnection = ::toggleConnection,
          startupUpdate = startupUpdate,
          startupUpdateError = startupUpdateError,
          onUpdaterLaunched = {
            exitApplication()
          },
          externalImportUrl = externalImportUrl,
          onExternalImportConsumed = { externalImportRequests.value = null },
        )
        }
      }
    }
  }
  }
}

/** Monochrome Veilark mark shared with the Android adaptive icon. */
internal fun brandBitmap(size: Int, darkTheme: Boolean = true, tray: Boolean = false): BufferedImage =
  BrandIcon.render(size, darkTheme, tray)

@Composable
private fun MacShell(
  session: VeilarkSession,
  selectedSection: MacSection,
  onSectionSelected: (MacSection) -> Unit,
  tick: Int,
  refresh: () -> Unit,
  onToggleConnection: () -> Unit,
  startupUpdate: MacUpdate?,
  startupUpdateError: String?,
  onUpdaterLaunched: () -> Unit,
  externalImportUrl: String? = null,
  onExternalImportConsumed: () -> Unit = {},
) {
  tick
  val shortcutModifier = Modifier.onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || !(event.isMetaPressed || event.isCtrlPressed)) {
      return@onPreviewKeyEvent false
    }
    val section = when (event.key) {
      Key.One -> MacSection.OVERVIEW
      Key.Two -> MacSection.PROFILES
      Key.Three -> MacSection.ROUTING
      Key.Four -> MacSection.DIAGNOSTICS
      Key.Five -> MacSection.SETTINGS
      else -> null
    } ?: return@onPreviewKeyEvent false
    onSectionSelected(section)
    true
  }

  Surface(modifier = Modifier.fillMaxSize().then(shortcutModifier)) {
    Row(Modifier.fillMaxSize()) {
      MacSidebar(
        session = session,
        selectedSection = selectedSection,
        onSectionSelected = onSectionSelected,
      )
      VerticalRule()
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .padding(horizontal = 36.dp, vertical = 28.dp),
      ) {
        when (selectedSection) {
          MacSection.OVERVIEW -> OverviewSection(session, onSectionSelected, onToggleConnection)
          MacSection.PROFILES -> ProfilesSection(
            session,
            refresh,
            externalImportUrl,
            onExternalImportConsumed,
          )
          MacSection.ROUTING -> RoutingSection(session)
          MacSection.DIAGNOSTICS -> DiagnosticsSection(session)
          MacSection.SETTINGS -> SettingsSection(
            session,
            refresh,
            startupUpdate,
            startupUpdateError,
            onUpdaterLaunched,
          )
        }
      }
    }
  }
}

@Composable
private fun MacSidebar(
  session: VeilarkSession,
  selectedSection: MacSection,
  onSectionSelected: (MacSection) -> Unit,
) {
  Surface(
    modifier = Modifier.widthIn(min = 220.dp, max = 240.dp).fillMaxHeight(),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(
      alpha = if (LocalVisualPreferences.current.reduceTransparency) 1f else 0.55f,
    ),
  ) {
    Column(
      modifier = Modifier.fillMaxHeight().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          imageVector = VeilarkMark,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(25.dp),
        )
        Column {
          Text(Strings.appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(Strings.macClient, style = MaterialTheme.typography.labelSmall)
        }
      }
      Spacer(Modifier.height(16.dp))
      StatusLine(session.status)
      Spacer(Modifier.height(12.dp))
      MacSection.entries.forEach { section ->
        SidebarItem(
          section = section,
          selected = selectedSection == section,
          onClick = { onSectionSelected(section) },
        )
      }
      Spacer(Modifier.weight(1f))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
      Text(
        Strings.keyboardHint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
      )
    }
  }
}

@Composable
private fun SidebarItem(
  section: MacSection,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val focusedState = remember { mutableStateOf(false) }
  val itemColor by animateColorAsState(
    targetValue = when {
      selected -> colors.secondaryContainer
      hovered -> colors.surface.copy(alpha = 0.7f)
      else -> Color.Transparent
    },
    animationSpec = tween(if (LocalVisualPreferences.current.reduceMotion) 0 else 140),
    label = "sidebar selection",
  )
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(9.dp))
      .clickable(interactionSource = interaction, indication = null, onClick = onClick)
      .focusable(interactionSource = interaction)
      .onFocusChanged { focusedState.value = it.isFocused }
      .semantics {
        role = Role.Tab
        this.selected = selected
        stateDescription = if (selected) Strings.selectedState else Strings.notSelectedState
      },
    color = itemColor,
    border = if (focusedState.value) BorderStroke(1.dp, colors.primary) else null,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
      Icon(
        imageVector = section.icon,
        contentDescription = null,
        tint = if (selected) colors.primary else colors.onSurfaceVariant,
        modifier = Modifier.size(19.dp),
      )
      Text(
        section.label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) colors.onSecondaryContainer else colors.onSurface,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
      )
    }
  }
}

@Composable
private fun OverviewSection(
  session: VeilarkSession,
  onSectionSelected: (MacSection) -> Unit,
  onToggleConnection: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  fun checkHealth() {
    scope.launch {
      runCatching { session.checkConnectionHealth() }
    }
  }
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(22.dp),
  ) {
    SectionHeading(Strings.overview, Strings.overviewSubtitle)
    ConnectionPanel(session, onToggleConnection, ::checkHealth)
    EngineChooser(session)
    CurrentProfile(session, onSectionSelected)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      OutlinedButton(onClick = { onSectionSelected(MacSection.PROFILES) }) {
        Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(Strings.openProfiles)
      }
      OutlinedButton(onClick = { onSectionSelected(MacSection.DIAGNOSTICS) }) {
        Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(Strings.openDiagnostics)
      }
    }
  }
}

@Composable
private fun ConnectionPanel(
  session: VeilarkSession,
  onToggleConnection: () -> Unit,
  onCheckHealth: () -> Unit,
) {
  val connected = session.status == TunnelStatus.CONNECTED
  val connecting = session.status == TunnelStatus.CONNECTING || session.status == TunnelStatus.RECONNECTING
  val unavailable = session.busy && !connecting
  val failed = session.status == TunnelStatus.FAILED || session.status == TunnelStatus.DEGRADED
  val colors = MaterialTheme.colorScheme
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = if (failed) colors.errorContainer else colors.primaryContainer,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(22.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Icon(
        imageVector = when {
          connected -> Icons.Outlined.CheckCircle
          failed -> Icons.Outlined.ErrorOutline
          else -> VeilarkMark
        },
        contentDescription = null,
        tint = if (failed) colors.error else colors.primary,
        modifier = Modifier.size(36.dp),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          when {
            connected -> Strings.connected
            session.status == TunnelStatus.CONNECTING -> Strings.connecting
            session.status == TunnelStatus.RECONNECTING -> RuntimeMessages.reconnecting
            session.status == TunnelStatus.DEGRADED -> RuntimeMessages.handoverDegraded
            failed -> Strings.failed
            else -> Strings.disconnected
          },
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        val detail = when {
          failed && session.statusDetail.isNotBlank() -> session.statusDetail
          connected -> Strings.engineActive(session.engine)
          else -> Strings.connectionReady
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium)
      }
      IconActionButton(
        icon = Icons.Outlined.Speed,
        description = Strings.checkHealth,
        enabled = connected && !session.busy,
        onClick = onCheckHealth,
      )
      Button(onClick = onToggleConnection, enabled = !unavailable) {
        if (connecting) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
        }
        Icon(
          imageVector = if (connected || connecting) Icons.Outlined.Close else Icons.Outlined.PowerSettingsNew,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
          when {
            connected -> Strings.disconnect
            connecting -> Strings.cancelConnection
            else -> Strings.connect
          },
        )
      }
    }
  }
}

@Composable
private fun EngineChooser(session: VeilarkSession) {
  Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
    Text(Strings.engine, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      EngineChoice(
        modifier = Modifier.weight(1f),
        title = Strings.trustTunnel,
        subtitle = Strings.trustTunnelSummary,
        selected = session.engine == TunnelEngineKind.TRUST_TUNNEL,
        enabled = !session.status.blocksOfflineChanges && !session.busy,
        icon = Icons.Outlined.Lock,
        onClick = { session.switchEngine(TunnelEngineKind.TRUST_TUNNEL) },
      )
      EngineChoice(
        modifier = Modifier.weight(1f),
        title = Strings.singBox,
        subtitle = Strings.singBoxSummary,
        selected = session.engine == TunnelEngineKind.SING_BOX,
        enabled = !session.status.blocksOfflineChanges && !session.busy,
        icon = Icons.Outlined.Tune,
        onClick = { session.switchEngine(TunnelEngineKind.SING_BOX) },
      )
    }
    if (session.status.blocksOfflineChanges) {
      Text(Strings.disconnectBeforeEngineSwitch, style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun EngineChoice(
  modifier: Modifier,
  title: String,
  subtitle: String,
  selected: Boolean,
  enabled: Boolean,
  icon: ImageVector,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(enabled = enabled, onClick = onClick)
      .semantics {
        role = Role.RadioButton
        this.selected = selected
        stateDescription = if (selected) Strings.selectedState else Strings.notSelectedState
      },
    shape = RoundedCornerShape(12.dp),
    color = if (selected) colors.secondaryContainer else colors.surface,
    border = BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = 0.55f) else colors.outlineVariant),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(13.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
      Icon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.onSurfaceVariant)
      Column(modifier = Modifier.weight(1f)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      }
      if (selected) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.primary)
    }
  }
}

@Composable
private fun CurrentProfile(session: VeilarkSession, onSectionSelected: (MacSection) -> Unit) {
  val profileName = when (session.engine) {
    TunnelEngineKind.SING_BOX -> session.singBoxEntries.firstOrNull { it.id == session.selectedSingBoxId }?.name
    TunnelEngineKind.TRUST_TUNNEL -> session.trustEntries.firstOrNull { it.id == session.selectedTrustId }?.name
  }
  Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
    Text(Strings.activeProfile, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (profileName.isNullOrBlank()) {
      EmptyState(
        icon = Icons.Outlined.Description,
        title = Strings.noProfiles,
        body = Strings.profileEmptyBody,
        action = Strings.openProfiles,
        onAction = { onSectionSelected(MacSection.PROFILES) },
      )
    } else {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
      ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(profileName, fontWeight = FontWeight.SemiBold)
            Text(Strings.engineActive(session.engine), style = MaterialTheme.typography.bodySmall)
          }
          Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
      }
    }
  }
}

@Composable
private fun ProfilesSection(
  session: VeilarkSession,
  refresh: () -> Unit,
  externalImportUrl: String? = null,
  onExternalImportConsumed: () -> Unit = {},
) {
  val scope = rememberCoroutineScope()
  var importText by remember { mutableStateOf("") }
  var importError by remember { mutableStateOf<String?>(null) }
  var subscriptionBotError by remember { mutableStateOf<String?>(null) }

  // A validated deep link prefills the same reviewed import field; the request is
  // cleared at once so that a recomposition cannot overwrite the user's edits.
  LaunchedEffect(externalImportUrl) {
    externalImportUrl?.let { value ->
      importText = value
      importError = null
      onExternalImportConsumed()
    }
  }
  Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
    SectionHeading(Strings.profiles, Strings.profilesSubtitle)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End,
    ) {
      OutlinedButton(
        onClick = {
          subscriptionBotError = if (TelegramBotLink.openConfigured()) {
            null
          } else {
            Strings.telegramOpenFailed
          }
        },
      ) {
        Text(Strings.getOrRenewSubscription)
      }
      Spacer(Modifier.width(8.dp))
      TextButton(
        onClick = {
          subscriptionBotError = if (VeilarkWebLink.openConfigured()) {
            null
          } else {
            Strings.webAppOpenFailed
          }
        },
      ) {
        Text(Strings.webApp)
      }
      Spacer(Modifier.width(8.dp))
      TextButton(
        onClick = {
          subscriptionBotError = if (
            TelegramBotLink.openConfigured(TelegramBotLink.Destination.SUPPORT)
          ) {
            null
          } else {
            Strings.telegramOpenFailed
          }
        },
      ) {
        Text(Strings.support)
      }
    }
    subscriptionBotError?.let { message ->
      Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
    ) {
      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Spacer(Modifier.width(9.dp))
          Text(Strings.addProfile, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        OutlinedTextField(
          value = importText,
          onValueChange = {
            importText = it
            importError = null
          },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(Strings.importHint) },
          minLines = 1,
          maxLines = 3,
          isError = importError != null,
          supportingText = importError?.let { message ->
            { Text(message) }
          },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          Button(
            onClick = {
              scope.launch {
                runCatching { session.importText(importText) }
                  .onSuccess {
                    importText = ""
                    importError = null
                  }
                  .onFailure { importError = it.message ?: Strings.importFailed }
                refresh()
              }
            },
            enabled = !session.busy && importText.isNotBlank(),
          ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(Strings.importAction)
          }
          IconActionButton(Icons.Outlined.ContentPaste, Strings.paste, !session.busy) {
            val text = MacClipboard.readPasteboard()
            if (!text.isNullOrBlank()) importText = text else session.log(Strings.clipboardEmpty)
            refresh()
          }
          IconActionButton(Icons.Outlined.FolderOpen, Strings.file, !session.busy) {
            val dialog = FileDialog(null as Frame?, Strings.file, FileDialog.LOAD)
            dialog.isVisible = true
            dialog.file?.let { selected ->
              val file = File(dialog.directory, selected)
              scope.launch {
                runCatching { session.importFile(file) }
                  .onFailure { importError = it.message ?: Strings.importFailed }
                refresh()
              }
            }
          }
          if (session.busy) LinearProgressIndicator(modifier = Modifier.weight(1f))
        }
      }
    }
    EngineChooser(session)
    ProfileActions(session)
    ProfileCatalog(session, refresh, Modifier.weight(1f))
  }
}

@Composable
private fun ProfileActions(session: VeilarkSession) {
  val scope = rememberCoroutineScope()
  var confirmDelete by remember { mutableStateOf(false) }
  val sourceUrl = when (session.engine) {
    TunnelEngineKind.SING_BOX -> session.singBoxEntries.firstOrNull { it.id == session.selectedSingBoxId }?.sourceUrl
    TunnelEngineKind.TRUST_TUNNEL -> session.trustEntries.firstOrNull { it.id == session.selectedTrustId }?.sourceUrl
  }
  val hasSelection = when (session.engine) {
    TunnelEngineKind.SING_BOX -> session.singBoxEntries.any { it.id == session.selectedSingBoxId }
    TunnelEngineKind.TRUST_TUNNEL -> session.trustEntries.any { it.id == session.selectedTrustId }
  }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(Strings.profileActions, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
    IconActionButton(
      icon = Icons.Outlined.NetworkCheck,
      description = Strings.checkHealth,
      enabled = session.status == TunnelStatus.CONNECTED && !session.busy,
      onClick = {
        scope.launch { runCatching { session.checkConnectionHealth() } }
      },
    )
    IconActionButton(
      icon = Icons.Outlined.Refresh,
      description = Strings.refreshSubscription,
      enabled = sourceUrl != null && session.status == TunnelStatus.DISCONNECTED && !session.busy,
      onClick = {
        scope.launch { runCatching { session.refreshSelectedSubscription() } }
      },
    )
    IconActionButton(
      icon = Icons.Outlined.Delete,
      description = Strings.deleteSubscription,
      enabled = hasSelection && session.status == TunnelStatus.DISCONNECTED && !session.busy,
      onClick = { confirmDelete = true },
    )
  }
  if (confirmDelete) {
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text(Strings.deleteSubscription) },
      text = { Text(Strings.deleteSubscriptionConfirm) },
      confirmButton = {
        TextButton(
          onClick = {
            runCatching { session.deleteSelectedSubscription() }
            confirmDelete = false
          },
        ) {
          Text(Strings.deleteSubscription, color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmDelete = false }) { Text(Strings.cancel) }
      },
    )
  }
}

@Composable
private fun ProfileCatalog(session: VeilarkSession, refresh: () -> Unit, modifier: Modifier = Modifier) {
  val entries = if (session.engine == TunnelEngineKind.SING_BOX) session.singBoxEntries else session.trustEntries
  if (entries.isEmpty()) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      EmptyState(Icons.Outlined.Description, Strings.noProfiles, Strings.profileEmptyBody, null, {})
    }
    return
  }
  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    item { Text(Strings.savedConnections, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
    if (session.engine == TunnelEngineKind.SING_BOX) {
      // Keep the subscription header and its nodes as separate lazy items.
      // A large subscription must not eagerly compose every node just because
      // its subscription is selected.
      session.singBoxEntries.forEach { entry ->
        singBoxEntryItems(session, entry, refresh)
      }
    } else {
      items(session.trustEntries, key = { it.id }) { entry ->
        SelectableRow(
          title = entry.name,
          subtitle = Strings.trustTunnel,
          selected = entry.id == session.selectedTrustId,
          icon = Icons.Outlined.Lock,
          onClick = { session.selectTrust(entry.id); refresh() },
        )
      }
    }
  }
}

private fun LazyListScope.singBoxEntryItems(
  session: VeilarkSession,
  entry: SingBoxCatalogEntry,
  refresh: () -> Unit,
) {
  val selected = entry.id == session.selectedSingBoxId
  item(key = "singbox-header-${entry.id}") {
    SelectableRow(
      title = entry.name,
      subtitle = "${entry.nodes.size} · ${entry.origin.wireName}",
      selected = selected,
      icon = Icons.Outlined.Tune,
      onClick = { session.selectSingBox(entry.id); refresh() },
    )
  }
  if (selected) {
    item(key = "singbox-servers-label-${entry.id}") {
      Text(Strings.servers, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 13.dp, top = 2.dp))
    }
    item(key = "singbox-automatic-${entry.id}") {
      SelectableRow(
        title = Strings.automatic,
        selected = entry.selectedNodeTag == ProfileSelection.AUTOMATIC_TAG,
        indent = true,
        icon = Icons.Outlined.NetworkCheck,
        onClick = {
          session.selectSingBox(entry.id, ProfileSelection.AUTOMATIC_TAG)
          refresh()
        },
      )
    }
    items(entry.nodes, key = { node -> "singbox-node-${entry.id}:${node.tag}" }) { node ->
      SelectableRow(
        title = node.name,
        subtitle = node.protocol,
        selected = entry.selectedNodeTag == node.tag,
        indent = true,
        icon = Icons.Outlined.Wifi,
        onClick = {
          session.selectSingBox(entry.id, node.tag)
          refresh()
        },
      )
    }
  }
}

@Composable
private fun RoutingSection(session: VeilarkSession) {
  val singBox = session.engine == TunnelEngineKind.SING_BOX
  var mode by remember(session.engine, session.routingMode) {
    mutableStateOf(session.routingMode)
  }
  var directEntries by remember(session.manualDirectEntries) { mutableStateOf(session.manualDirectEntries) }
  var vpnEntries by remember(session.manualVpnEntries) { mutableStateOf(session.manualVpnEntries) }
  var saveMessage by remember { mutableStateOf<String?>(null) }
  var saveError by remember { mutableStateOf<String?>(null) }
  var geoUpdating by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val canEdit = session.status == TunnelStatus.DISCONNECTED && !session.busy
  val geoAvailable = if (singBox) session.geoRuleSetsPresent() else session.geoIpRuPresent()

  fun applyMode(next: String) {
    mode = next
    saveMessage = null
    saveError = null
    if (next != ProfileSelection.ROUTING_MANUAL) {
      runCatching { session.updateRouting(next, directEntries, vpnEntries) }
        .onSuccess { saveMessage = Strings.routingSaved }
        .onFailure { error -> saveError = error.message ?: Strings.routingError }
    }
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      SectionHeading(Strings.routing, Strings.routingSubtitle, Modifier.weight(1f))
      OutlinedButton(
        onClick = {
          geoUpdating = true
          saveMessage = null
          saveError = null
          scope.launch {
            runCatching { session.refreshGeoData() }
              .onSuccess { saveMessage = Strings.geoUpdated }
              .onFailure { saveError = Strings.geoUpdateFailed }
            geoUpdating = false
          }
        },
        enabled = canEdit && !geoUpdating,
      ) {
        if (geoUpdating) {
          CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
        } else {
          Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(Strings.updateGeo)
      }
    }
    if (!singBox) {
      CapabilityRow(Icons.Outlined.Tune, Strings.trustTunnel, Strings.trustRoutingDescription, Strings.engine)
    } else {
      CapabilityRow(Icons.Outlined.Tune, Strings.singBox, Strings.singRoutingDescription, Strings.engine)
    }

    Text(Strings.routingMode, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    RoutingOption(
      selected = mode == ProfileSelection.ROUTING_ALL,
      enabled = canEdit,
      title = Strings.fullTunnel,
      body = if (singBox) Strings.fullTunnelHint else Strings.trustFullTunnelHint,
      onClick = { applyMode(ProfileSelection.ROUTING_ALL) },
    )
    RoutingOption(
      selected = mode == ProfileSelection.ROUTING_RU_DIRECT,
      enabled = canEdit && geoAvailable,
      title = Strings.ruDirect,
      body = if (geoAvailable) {
        if (singBox) Strings.ruDirectHint else Strings.trustRuDirectHint
      } else {
        Strings.geoRuleSetsMissing
      },
      onClick = { applyMode(ProfileSelection.ROUTING_RU_DIRECT) },
    )
    RoutingOption(
      selected = mode == ProfileSelection.ROUTING_MANUAL,
      enabled = canEdit,
      title = Strings.manualRouting,
      body = Strings.manualRoutingHint,
      onClick = { mode = ProfileSelection.ROUTING_MANUAL; saveMessage = null; saveError = null },
    )

    if (mode == ProfileSelection.ROUTING_MANUAL) {
      OutlinedTextField(
        value = directEntries,
        onValueChange = { directEntries = it; saveMessage = null; saveError = null },
        enabled = canEdit,
        modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp),
        label = { Text(Strings.directRules) },
        placeholder = { Text(Strings.manualDirectPlaceholder) },
        minLines = 3,
      )
      OutlinedTextField(
        value = vpnEntries,
        onValueChange = { vpnEntries = it; saveMessage = null; saveError = null },
        enabled = canEdit,
        modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp),
        label = { Text(Strings.vpnRules) },
        placeholder = { Text(Strings.manualVpnPlaceholder) },
        minLines = 3,
      )
      Button(
        onClick = {
          scope.launch {
            runCatching { session.updateRouting(ProfileSelection.ROUTING_MANUAL, directEntries, vpnEntries) }
              .onSuccess { saveMessage = Strings.routingSaved }
              .onFailure { error -> saveError = error.message ?: Strings.routingError }
          }
        },
        enabled = canEdit && (directEntries.isNotBlank() || vpnEntries.isNotBlank()),
      ) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(Strings.saveRouting)
      }
    }

    if (session.status == TunnelStatus.CONNECTED) {
      Text(Strings.routingLiveNotice, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    saveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    saveError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
  }
}

@Composable
private fun RoutingOption(
  selected: Boolean,
  enabled: Boolean,
  title: String,
  body: String,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(enabled = enabled, onClick = onClick)
      .semantics {
        role = Role.RadioButton
        this.selected = selected
        stateDescription = if (selected) Strings.selectedState else Strings.notSelectedState
      },
    color = if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.28f),
    contentColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.54f),
    border = if (selected) BorderStroke(1.dp, colors.primary.copy(alpha = 0.65f)) else null,
  ) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(
        imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.Route,
        contentDescription = null,
        tint = if (selected) colors.primary else colors.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun DiagnosticsSection(session: VeilarkSession) {
  val scope = rememberCoroutineScope()
  Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(15.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      SectionHeading(Strings.diagnostics, Strings.diagnosticsSubtitle, Modifier.weight(1f))
      IconActionButton(
        Icons.Outlined.NetworkCheck,
        Strings.checkHealth,
        session.status == TunnelStatus.CONNECTED && !session.busy,
      ) { scope.launch { runCatching { session.checkConnectionHealth() } } }
      IconActionButton(Icons.Outlined.ContentCopy, Strings.copyLogs, session.logs.isNotEmpty()) {
        MacClipboard.writePasteboard(session.logs.takeLast(100).joinToString("\n") { formatLog(it) })
      }
      IconActionButton(Icons.Outlined.Delete, Strings.clearLog, session.logs.isNotEmpty()) {
        session.clearLogs()
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatusMetric(Strings.tunnelStatus, session.statusLabel())
      StatusMetric(Strings.helperStatus, if (session.helperReady()) Strings.ready else Strings.notInstalled)
      StatusMetric(
        Strings.engineStatus,
        if (session.enginePresent(TunnelEngineKind.SING_BOX) && session.enginePresent(TunnelEngineKind.TRUST_TUNNEL)) Strings.ready else Strings.missing,
      )
    }
    if (session.lastHealthDetail.isNotBlank()) {
      Text("${Strings.healthDetail}: ${session.lastHealthDetail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    session.storageWarning?.let {
      Text("${Strings.storageWarning}: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Surface(
      modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 520.dp),
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
    ) {
      if (session.logs.isEmpty()) {
        EmptyState(Icons.Outlined.NetworkCheck, Strings.technicalLogEmpty, Strings.logAppearsAfterAction, null, {})
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(session.logs.takeLast(100)) { entry -> LogRow(entry) }
        }
      }
    }
  }
}

@Composable
private fun SettingsSection(
  session: VeilarkSession,
  refresh: () -> Unit,
  startupUpdate: MacUpdate?,
  startupUpdateError: String?,
  onUpdaterLaunched: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var helperBusy by remember { mutableStateOf(false) }
  var helperError by remember { mutableStateOf<String?>(null) }
  var update by remember(startupUpdate) { mutableStateOf(startupUpdate) }
  var updateBusy by remember { mutableStateOf(false) }
  var updateStatus by remember { mutableStateOf<String?>(null) }
  var updateError by remember(startupUpdateError) { mutableStateOf(startupUpdateError) }
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    SectionHeading(Strings.settings, Strings.settingsSubtitle)
    SettingsRow(Icons.Outlined.Tune, Strings.singBox, Strings.singBoxVersion, true)
    SettingsRow(Icons.Outlined.Lock, Strings.trustTunnel, Strings.trustTunnelVersion, true)
    SettingsRow(VeilarkMark, Strings.privilegedHelper, if (session.helperReady()) Strings.installed else Strings.notInstalled, session.helperReady())
    if (!session.helperReady()) {
      OutlinedButton(
        onClick = {
          scope.launch {
            helperBusy = true
            helperError = null
            runCatching { session.installHelper().getOrThrow() }
              .onFailure { helperError = it.message ?: Strings.helperInstallFailed }
            helperBusy = false
            refresh()
          }
        },
        enabled = !helperBusy && !session.busy && session.status == TunnelStatus.DISCONNECTED,
      ) {
        if (helperBusy) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
          Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(Strings.installHelper)
      }
      if (session.status != TunnelStatus.DISCONNECTED) {
        Text(
          Strings.disconnectBeforeHelperInstall,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      helperError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
    }
    SettingsRow(
      Icons.Outlined.Info,
      Strings.updateChannel,
      when {
        !MacUpdateClient.configured -> Strings.updateChannelNotReady
        MacUpdateClient.gatekeeperRequired -> Strings.updateChannelVerified
        else -> Strings.updateChannelPreview
      },
      MacUpdateClient.configured,
    )
    if (MacUpdateClient.configured) {
      OutlinedButton(
        onClick = {
          scope.launch {
            updateBusy = true
            updateStatus = null
            updateError = null
            runCatching { MacUpdateClient.check() }
              .onSuccess { available ->
                update = available
                if (available == null) updateStatus = Strings.noUpdates
              }
              .onFailure { updateError = it.message ?: Strings.updateCheckFailed }
            updateBusy = false
          }
        },
        enabled = !updateBusy,
      ) {
        if (updateBusy) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
          Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(Strings.checkUpdates)
      }
    }
    update?.let { available ->
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Veilark ${available.version}", fontWeight = FontWeight.SemiBold)
            Text(
              available.notes.ifBlank { Strings.signedUpdateReady },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Button(
            onClick = {
              scope.launch {
                updateBusy = true
                updateError = null
                runCatching {
                  val file = MacUpdateClient.download(available)
                  session.stopForQuit().getOrThrow()
                  MacUpdateClient.launchInstaller(available, file)
                }
                  .onSuccess { onUpdaterLaunched() }
                  .onFailure { updateError = it.message ?: Strings.updateDownloadFailed }
                updateBusy = false
              }
            },
            enabled = !updateBusy,
          ) {
            Text(Strings.installUpdate)
          }
        }
      }
    }
    updateStatus?.let {
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    updateError?.let {
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    SettingsRow(Icons.Outlined.Info, Strings.about, Strings.aboutDescription, true)
  }
}

@Composable
private fun SectionHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun StatusLine(status: TunnelStatus) {
  val connected = status == TunnelStatus.CONNECTED
  val failed = status == TunnelStatus.FAILED || status == TunnelStatus.DEGRADED
  val color = when {
    connected -> MaterialTheme.colorScheme.primary
    failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Surface(modifier = Modifier.size(8.dp), shape = RoundedCornerShape(50), color = color) {}
    Text(
      when {
        connected -> Strings.connected
        status == TunnelStatus.CONNECTING -> Strings.connecting
        status == TunnelStatus.RECONNECTING -> RuntimeMessages.reconnecting
        status == TunnelStatus.DEGRADED -> RuntimeMessages.handoverDegraded
        failed -> Strings.failed
        else -> Strings.disconnected
      },
      style = MaterialTheme.typography.labelMedium,
      color = color,
    )
  }
}

@Composable
private fun StatusMetric(label: String, value: String) {
  Column(modifier = Modifier.widthIn(min = 120.dp).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun CapabilityRow(icon: ImageVector, title: String, body: String, state: String) {
  Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
      }
      Text(state, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, value: String, enabled: Boolean) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(title, fontWeight = FontWeight.SemiBold)
      Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Icon(imageVector = if (enabled) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun SelectableRow(
  title: String,
  subtitle: String? = null,
  selected: Boolean,
  indent: Boolean = false,
  icon: ImageVector,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val focusedState = remember { mutableStateOf(false) }
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = if (indent) 17.dp else 0.dp)
      .clip(RoundedCornerShape(10.dp))
      .clickable(interactionSource = interaction, indication = null, onClick = onClick)
      .focusable(interactionSource = interaction)
      .onFocusChanged { focusedState.value = it.isFocused }
      .semantics {
        role = Role.RadioButton
        this.selected = selected
        stateDescription = if (selected) Strings.selectedState else Strings.notSelectedState
      },
    color = when {
      selected -> colors.primaryContainer
      hovered -> colors.surfaceVariant.copy(alpha = 0.55f)
      else -> Color.Transparent
    },
    border = if (focusedState.value) BorderStroke(1.dp, colors.primary) else null,
  ) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
      Icon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      }
      if (selected) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String, action: String?, onAction: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (action != null) TextButton(onClick = onAction) { Text(action) }
  }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun IconActionButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
  TooltipBox(
    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
    tooltip = { PlainTooltip { Text(description) } },
    state = rememberTooltipState(),
  ) {
    IconButton(onClick = onClick, enabled = enabled) {
      Icon(icon, contentDescription = description)
    }
  }
}

@Composable
private fun LogRow(entry: LogEntry) {
  Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(formatTime(entry), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(entry.message, style = MaterialTheme.typography.bodySmall)
      Text(
        buildString {
          append(entry.level.name)
          append(" · ")
          append(entry.component)
          entry.code?.let { append(" · "); append(it) }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun formatTime(entry: LogEntry): String = TIME_FORMAT.format(entry.at.atZone(ZoneId.systemDefault()))

private fun formatLog(entry: LogEntry): String = buildString {
  append(formatTime(entry)); append("  "); append(entry.level.name); append("  ")
  append(entry.component); entry.code?.let { append("  "); append(it) }
  append("  "); append(entry.message)
}

private fun VeilarkSession.statusLabel(): String = when (status) {
  TunnelStatus.CONNECTED -> Strings.connected
  TunnelStatus.CONNECTING -> Strings.connecting
  TunnelStatus.RECONNECTING -> RuntimeMessages.reconnecting
  TunnelStatus.FAILED -> Strings.failed
  TunnelStatus.DEGRADED -> RuntimeMessages.handoverDegraded
  TunnelStatus.DISCONNECTED -> Strings.disconnected
}

@Composable
private fun VerticalRule() {
  Box(
    modifier = Modifier
      .fillMaxHeight()
      .width(1.dp)
      .padding(0.dp),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    ) {}
  }
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
