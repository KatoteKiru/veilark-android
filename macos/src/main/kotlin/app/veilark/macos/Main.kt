package app.veilark.macos

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.veilark.engine.TunnelEngineKind
import com.example.veilark.engine.TunnelStatus
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.session.LogEntry
import com.example.veilark.session.LogLevel
import com.example.veilark.session.VeilarkSession
import com.example.veilark.theme.VeilarkTheme
import com.example.veilark.ui.MacClipboard
import com.example.veilark.ui.Strings
import com.example.veilark.update.MacUpdate
import com.example.veilark.update.MacUpdateClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Color as AwtColor
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Section(val icon: ImageVector) {
  OVERVIEW(Icons.Rounded.Home),
  PROFILES(Icons.AutoMirrored.Rounded.List),
  ROUTING(Icons.Rounded.Route),
  DIAGNOSTICS(Icons.Rounded.Description),
  SETTINGS(Icons.Rounded.Settings),
}

fun main() = application {
  val session = remember { VeilarkSession.createDefault() }
  val scope = rememberCoroutineScope()
  var visible by remember { mutableStateOf(true) }
  val trayIcon = remember { BitmapPainter(trayBitmap().toComposeImageBitmap()) }

  LaunchedEffect(session) {
    while (true) {
      delay(10_000)
      session.reconcileStatus()
    }
  }

  Tray(
    icon = trayIcon,
    tooltip = Strings.appName,
    onAction = { visible = true },
    menu = {
      Item(Strings.open, onClick = { visible = true })
      Item(
        if (session.status == TunnelStatus.CONNECTED) Strings.disconnect else Strings.connect,
        enabled = !session.busy,
        onClick = {
          if (session.status == TunnelStatus.CONNECTED) session.disconnect()
          else scope.launch { session.connect() }
        },
      )
      Separator()
      Item(Strings.quit, onClick = {
        if (session.status != TunnelStatus.DISCONNECTED) session.disconnect()
        exitApplication()
      })
    },
  )

  if (visible) {
    Window(
      onCloseRequest = { visible = false },
      title = Strings.appName,
      state = rememberWindowState(width = 1080.dp, height = 760.dp),
    ) {
      VeilarkTheme {
        App(session)
      }
    }
  }
}

private fun trayBitmap(): BufferedImage = BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB).also { image ->
  image.createGraphics().run {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    color = AwtColor(0x27, 0x5D, 0x8C)
    fillOval(2, 2, 14, 14)
    color = AwtColor.WHITE
    fillOval(7, 7, 4, 4)
    dispose()
  }
}

@Composable
private fun App(session: VeilarkSession) {
  var section by remember { mutableStateOf(Section.OVERVIEW) }
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Row(Modifier.fillMaxSize()) {
      Sidebar(section) { section = it }
      VerticalDivider()
      Column(Modifier.weight(1f).fillMaxHeight()) {
        Row(
          Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 28.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(sectionTitle(section), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider()
        AnimatedContent(section, label = "section") { current ->
          when (current) {
            Section.OVERVIEW -> Overview(session)
            Section.PROFILES -> Profiles(session)
            Section.ROUTING -> Routing(session)
            Section.DIAGNOSTICS -> Diagnostics(session)
            Section.SETTINGS -> Settings(session)
          }
        }
      }
    }
  }
}

@Composable
private fun Sidebar(selected: Section, onSelect: (Section) -> Unit) {
  Surface(
    Modifier.width(224.dp).fillMaxHeight(),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
  ) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
      Row(
        Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
          Icon(Icons.Rounded.Lock, null, Modifier.padding(8.dp).size(18.dp), MaterialTheme.colorScheme.onPrimary)
        }
        Text(Strings.appName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
      }
      Spacer(Modifier.height(14.dp))
      Section.entries.forEach { item ->
        val background by animateColorAsState(
          if (item == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
          label = "nav",
        )
        Surface(
          Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onSelect(item) },
          color = background,
          shape = RoundedCornerShape(10.dp),
        ) {
          Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(item.icon, null, Modifier.size(19.dp))
            Text(sectionTitle(item), fontWeight = if (item == selected) FontWeight.SemiBold else FontWeight.Normal)
          }
        }
      }
      Spacer(Modifier.weight(1f))
      Text("macOS · 1.0.1-dev", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
    }
  }
}

@Composable
private fun Overview(session: VeilarkSession) {
  val scope = rememberCoroutineScope()
  Page {
    Label(Strings.quickStatus)
    ConnectionPanel(session) {
      scope.launch {
        if (session.status == TunnelStatus.CONNECTED) session.disconnect() else session.connect()
      }
    }
    Label(Strings.networkCore)
    EngineSelector(session)
    Label(Strings.selectedProfile)
    ProfilePicker(session)
    if (session.lastHealthDetail.isNotBlank()) Notice(Icons.Rounded.Public, session.lastHealthDetail)
    session.storageWarning?.let { Notice(Icons.Rounded.Warning, it, true) }
    if (!session.helperReady()) Notice(Icons.Rounded.Build, Strings.helperMissing, true)
    if (!session.enginePresent()) Notice(Icons.Rounded.Warning, Strings.enginesMissing, true)
  }
}

@Composable
private fun ConnectionPanel(session: VeilarkSession, onToggle: () -> Unit) {
  val connected = session.status == TunnelStatus.CONNECTED
  val statusColor by animateColorAsState(
    when (session.status) {
      TunnelStatus.CONNECTED -> Color(0xFF2E7D4F)
      TunnelStatus.FAILED -> MaterialTheme.colorScheme.error
      TunnelStatus.CONNECTING -> MaterialTheme.colorScheme.primary
      TunnelStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    },
    label = "status",
  )
  Surface(
    Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(22.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Surface(shape = CircleShape, color = statusColor.copy(alpha = 0.13f)) {
        Icon(
          if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.PowerSettingsNew,
          null,
          Modifier.padding(13.dp).size(28.dp),
          statusColor,
        )
      }
      Column(Modifier.weight(1f)) {
        AnimatedContent(session.status, label = "status-copy") {
          Text(statusTitle(it, session.statusDetail), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Text(
          if (connected) engineTitle(session.engine) else profileName(session),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Button(onClick = onToggle, enabled = !session.busy, modifier = Modifier.width(154.dp)) {
        Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (connected) Strings.disconnect else if (session.busy) Strings.connecting else Strings.connect)
      }
    }
  }
}

@Composable
private fun EngineSelector(session: VeilarkSession) {
  val enabled = session.status != TunnelStatus.CONNECTED &&
    session.status != TunnelStatus.CONNECTING &&
    !session.busy
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    EngineButton(Strings.trustTunnel, session.engine == TunnelEngineKind.TRUST_TUNNEL, enabled) {
      session.switchEngine(TunnelEngineKind.TRUST_TUNNEL)
    }
    EngineButton(Strings.singBox, session.engine == TunnelEngineKind.SING_BOX, enabled) {
      session.switchEngine(TunnelEngineKind.SING_BOX)
    }
  }
}

@Composable
private fun EngineButton(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  if (selected) Button(onClick, enabled = enabled) {
    Icon(Icons.Rounded.Lock, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(title)
  } else OutlinedButton(onClick, enabled = enabled) {
    Icon(Icons.Rounded.Public, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(title)
  }
}

@Composable
private fun ProfilePicker(session: VeilarkSession) {
  var expanded by remember { mutableStateOf(false) }
  val candidates = if (session.engine == TunnelEngineKind.SING_BOX) {
    session.singBoxEntries.map { it.id to it.name }
  } else session.trustEntries.map { it.id to it.name }
  Box {
    OutlinedButton(
      { expanded = true },
      enabled = candidates.isNotEmpty() && !session.busy && session.status != TunnelStatus.CONNECTED,
    ) {
      Text(profileName(session), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DropdownMenu(expanded, { expanded = false }) {
      candidates.forEach { (id, name) ->
        DropdownMenuItem(
          text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          onClick = {
            if (session.engine == TunnelEngineKind.SING_BOX) session.selectSingBox(id) else session.selectTrust(id)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
private fun Profiles(session: VeilarkSession) {
  val scope = rememberCoroutineScope()
  var showImport by remember { mutableStateOf(false) }
  var importText by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var confirmDelete by remember { mutableStateOf(false) }
  if (confirmDelete) DeleteDialog(profileName(session), { confirmDelete = false }) {
    error = runCatching { session.deleteSelectedSubscription() }.exceptionOrNull()?.message
    confirmDelete = false
  }
  Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      EngineSelector(session)
      Spacer(Modifier.weight(1f))
      Action(
        Icons.Rounded.Add,
        Strings.addSubscription,
        !session.busy && session.status != TunnelStatus.CONNECTED,
      ) { showImport = !showImport }
      Action(Icons.Rounded.Refresh, Strings.refreshSubscription, !session.busy && session.status != TunnelStatus.CONNECTED) {
        scope.launch { error = runCatching { session.refreshSelectedSubscription() }.exceptionOrNull()?.message }
      }
      Action(
        Icons.Rounded.Delete,
        Strings.deleteSubscription,
        !session.busy && session.status != TunnelStatus.CONNECTED && profileName(session) != Strings.noSelectedProfile,
      ) {
        confirmDelete = true
      }
    }
    AnimatedVisibility(showImport) {
      ImportPanel(importText, { importText = it }, session, { error = it }) {
        importText = ""; showImport = false
      }
    }
    error?.let { Notice(Icons.Rounded.Warning, it, true) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
      Column(Modifier.weight(1f).fillMaxHeight()) {
        Label(Strings.profiles); Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize()) {
          if (session.engine == TunnelEngineKind.SING_BOX) {
            items(session.singBoxEntries, key = { it.id }) { entry ->
              SelectRow(
                entry.name,
                "${entry.nodes.size} · ${entry.origin.wireName}",
                entry.id == session.selectedSingBoxId,
                session.status != TunnelStatus.CONNECTED,
              ) {
                session.selectSingBox(entry.id)
              }
            }
          } else items(session.trustEntries, key = { it.id }) { entry ->
            SelectRow(
              entry.name,
              "TrustTunnel · ${entry.origin.wireName}",
              entry.id == session.selectedTrustId,
              session.status != TunnelStatus.CONNECTED,
            ) {
              session.selectTrust(entry.id)
            }
          }
          if ((session.engine == TunnelEngineKind.SING_BOX && session.singBoxEntries.isEmpty()) ||
            (session.engine == TunnelEngineKind.TRUST_TUNNEL && session.trustEntries.isEmpty())) item { Empty(Strings.noProfiles) }
        }
      }
      Column(Modifier.weight(1f).fillMaxHeight()) {
        Label(Strings.servers); Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxSize()) {
          if (session.engine == TunnelEngineKind.SING_BOX) {
            item {
              NodeRow(
                Strings.automatic,
                "urltest",
                selectedSing(session)?.selectedNodeTag == ProfileSelection.AUTOMATIC_TAG,
                session.status != TunnelStatus.CONNECTED,
              ) {
                session.selectedSingBoxId?.let { session.selectSingBox(it, ProfileSelection.AUTOMATIC_TAG) }
              }
            }
            items(selectedSing(session)?.nodes.orEmpty(), key = { it.tag }) { node ->
              NodeRow(
                node.name,
                node.protocol,
                selectedSing(session)?.selectedNodeTag == node.tag,
                session.status != TunnelStatus.CONNECTED,
              ) {
                session.selectedSingBoxId?.let { session.selectSingBox(it, node.tag) }
              }
            }
          } else {
            val selected = session.trustEntries.firstOrNull { it.id == session.selectedTrustId }
            item {
              if (selected == null) Empty(Strings.noSelectedProfile)
              else NodeRow(selected.name, "TrustTunnel", true, false) {}
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ImportPanel(
  value: String,
  onChange: (String) -> Unit,
  session: VeilarkSession,
  onError: (String?) -> Unit,
  onSuccess: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(Strings.importHint) }, minLines = 2, maxLines = 5)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button({
          scope.launch {
            val failure = runCatching { session.importText(value) }.exceptionOrNull()
            onError(failure?.message); if (failure == null) onSuccess()
          }
        }, enabled = value.isNotBlank() && !session.busy) { Text(Strings.importAction) }
        Action(Icons.Rounded.ContentPaste, Strings.paste) {
          MacClipboard.readPasteboard()?.let(onChange) ?: onError("Буфер обмена пуст")
        }
        Action(Icons.Rounded.FolderOpen, Strings.file) {
          val dialog = FileDialog(null as Frame?, Strings.file, FileDialog.LOAD).also { it.isVisible = true }
          dialog.file?.let { File(dialog.directory, it) }?.let { file ->
            scope.launch {
              val failure = runCatching { session.importFile(file) }.exceptionOrNull()
              onError(failure?.message); if (failure == null) onSuccess()
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Routing(session: VeilarkSession) {
  var mode by remember { mutableStateOf(session.routingMode) }
  var direct by remember { mutableStateOf(session.manualDirectEntries) }
  var vpn by remember { mutableStateOf(session.manualVpnEntries) }
  var message by remember { mutableStateOf<String?>(null) }
  Page {
    Notice(Icons.Rounded.Info, Strings.trustRoutingLimit)
    RouteChoice(Strings.fullTunnel, Strings.fullTunnelHint, mode == ProfileSelection.ROUTING_ALL) { mode = ProfileSelection.ROUTING_ALL }
    RouteChoice(Strings.ruDirect, Strings.ruDirectHint, mode == ProfileSelection.ROUTING_RU_DIRECT, session.geoRuleSetsPresent()) {
      mode = ProfileSelection.ROUTING_RU_DIRECT
    }
    RouteChoice(Strings.manualRouting, Strings.manualRoutingHint, mode == ProfileSelection.ROUTING_MANUAL) {
      mode = ProfileSelection.ROUTING_MANUAL
    }
    AnimatedVisibility(mode == ProfileSelection.ROUTING_MANUAL) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(direct, { direct = it }, Modifier.weight(1f), label = { Text(Strings.directRules) }, minLines = 5)
        OutlinedTextField(vpn, { vpn = it }, Modifier.weight(1f), label = { Text(Strings.vpnRules) }, minLines = 5)
      }
    }
    Button({
      val failure = runCatching { session.updateRouting(mode, direct, vpn) }.exceptionOrNull()
      message = failure?.message ?: Strings.routingSaved
    }, enabled = session.status != TunnelStatus.CONNECTED && !session.busy) { Text(Strings.save) }
    message?.let { Notice(if (it == Strings.routingSaved) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, it, it != Strings.routingSaved) }
  }
}

@Composable
private fun Diagnostics(session: VeilarkSession) {
  Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Label(Strings.log); Spacer(Modifier.weight(1f))
      Action(Icons.Rounded.ContentCopy, Strings.copyLog, session.logs.isNotEmpty()) {
        MacClipboard.writePasteboard(formatLogs(session.logs))
      }
      Action(Icons.Rounded.ClearAll, Strings.clearLog, session.logs.isNotEmpty()) { session.clearLogs() }
    }
    HorizontalDivider()
    LazyColumn(Modifier.fillMaxSize()) {
      if (session.logs.isEmpty()) item { Empty(Strings.technicalLogEmpty) }
      items(session.logs.asReversed()) { LogRow(it) }
    }
  }
}

@Composable
private fun Settings(session: VeilarkSession) {
  val scope = rememberCoroutineScope()
  var error by remember { mutableStateOf<String?>(null) }
  var update by remember { mutableStateOf<MacUpdate?>(null) }
  var updateStatus by remember { mutableStateOf<String?>(null) }
  var updateBusy by remember { mutableStateOf(false) }
  Page {
    Label(Strings.installComponents)
    Setting(Icons.Rounded.Build, "VPN helper", if (session.helperReady()) Strings.ready else Strings.notInstalled,
      if (session.helperReady()) null else Strings.installHelper) {
      scope.launch { error = session.installHelper().exceptionOrNull()?.message }
    }
    Setting(Icons.Rounded.Public, "sing-box", "1.13.19 · stable")
    Setting(Icons.Rounded.Lock, "TrustTunnel", "1.0.49 · stable")
    HorizontalDivider()
    Label(Strings.updateChannel)
    Setting(
      Icons.Rounded.Refresh,
      "Veilark OTA",
      if (MacUpdateClient.configured) "Ed25519 · SHA-256 · Gatekeeper" else Strings.updatesUnavailable,
      if (MacUpdateClient.configured && !updateBusy) Strings.checkUpdates else null,
    ) {
      scope.launch {
        updateBusy = true
        val result = runCatching { MacUpdateClient.check() }
        update = result.getOrNull()
        error = result.exceptionOrNull()?.message
        updateStatus = if (result.isSuccess && update == null) Strings.noUpdates else null
        updateBusy = false
      }
    }
    update?.let { available ->
      Setting(
        Icons.Rounded.SystemUpdate,
        "Veilark ${available.version}",
        available.notes.ifBlank { "Signed macOS update" },
        if (!updateBusy) Strings.downloadUpdate else null,
      ) {
        scope.launch {
          updateBusy = true
          val result = runCatching { MacUpdateClient.download(available) }
          error = result.exceptionOrNull()?.message
          result.getOrNull()?.let(MacUpdateClient::openInstaller)
          updateBusy = false
        }
      }
    }
    updateStatus?.let { Notice(Icons.Rounded.CheckCircle, it) }
    error?.let { Notice(Icons.Rounded.Warning, it, true) }
  }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) = Column(
  Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
  verticalArrangement = Arrangement.spacedBy(20.dp),
  content = content,
)

@Composable private fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

@Composable
private fun Action(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) = IconButton(
  onClick,
  enabled = enabled,
  modifier = Modifier.semantics { contentDescription = description },
) { Icon(icon, null) }

@Composable
private fun Notice(icon: ImageVector, text: String, warning: Boolean = false) = Row(
  Modifier.fillMaxWidth(),
  verticalAlignment = Alignment.Top,
  horizontalArrangement = Arrangement.spacedBy(10.dp),
) {
  Icon(icon, null, Modifier.size(19.dp), if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
  Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RouteChoice(title: String, subtitle: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
  Surface(
    Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      RadioButton(selected, onClick, enabled = enabled)
      Spacer(Modifier.width(10.dp))
      Column { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
    }
  }
}

@Composable
private fun SelectRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  Surface(
    Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
    shape = RoundedCornerShape(10.dp),
  ) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.Public, null, Modifier.size(19.dp))
      Column(Modifier.weight(1f)) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun NodeRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) = Row(
  Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(10.dp),
  verticalAlignment = Alignment.CenterVertically,
  horizontalArrangement = Arrangement.spacedBy(10.dp),
) {
  Surface(Modifier.size(8.dp), shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {}
  Column { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(subtitle, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun Setting(icon: ImageVector, title: String, subtitle: String, action: String? = null, onAction: () -> Unit = {}) {
  Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Icon(icon, null, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
    Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
    if (action != null) OutlinedButton(onAction) { Text(action) }
  }
}

@Composable
private fun LogRow(entry: LogEntry) {
  val color = when (entry.level) {
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    LogLevel.WARNING -> Color(0xFF9A6200)
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
  }
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(LOG_TIME.format(entry.at), style = MaterialTheme.typography.labelSmall)
    Text(entry.component, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(92.dp))
    Column(Modifier.weight(1f)) {
      Text(entry.message, style = MaterialTheme.typography.bodySmall, color = color)
      entry.code?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
  }
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

@Composable
private fun DeleteDialog(name: String, dismiss: () -> Unit, confirm: () -> Unit) = AlertDialog(
  onDismissRequest = dismiss,
  title = { Text(Strings.deleteSubscription) },
  text = { Text(name) },
  confirmButton = { TextButton(confirm) { Text(Strings.deleteSubscription, color = MaterialTheme.colorScheme.error) } },
  dismissButton = { TextButton(dismiss) { Text("Cancel") } },
)

@Composable
private fun Empty(text: String) = Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
  Icon(Icons.Rounded.Info, null); Spacer(Modifier.height(8.dp)); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun selectedSing(session: VeilarkSession) = session.singBoxEntries.firstOrNull { it.id == session.selectedSingBoxId }
private fun profileName(session: VeilarkSession): String = when (session.engine) {
  TunnelEngineKind.SING_BOX -> selectedSing(session)?.name
  TunnelEngineKind.TRUST_TUNNEL -> session.trustEntries.firstOrNull { it.id == session.selectedTrustId }?.name
} ?: Strings.noSelectedProfile

private fun engineTitle(engine: TunnelEngineKind) = if (engine == TunnelEngineKind.SING_BOX) Strings.singBox else Strings.trustTunnel
private fun statusTitle(status: TunnelStatus, detail: String) = when (status) {
  TunnelStatus.CONNECTED -> Strings.connected
  TunnelStatus.CONNECTING -> Strings.connecting
  TunnelStatus.FAILED -> detail.ifBlank { Strings.failed }
  TunnelStatus.DISCONNECTED -> Strings.disconnected
}
private fun sectionTitle(section: Section) = when (section) {
  Section.OVERVIEW -> Strings.overview
  Section.PROFILES -> Strings.profiles
  Section.ROUTING -> Strings.routing
  Section.DIAGNOSTICS -> Strings.diagnostics
  Section.SETTINGS -> Strings.settings
}
private fun formatLogs(logs: List<LogEntry>) = logs.joinToString("\n") {
  "${LOG_TIME.format(it.at)} [${it.level}] ${it.component}${it.code?.let { code -> "/$code" }.orEmpty()}: ${it.message}"
}
private val LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
