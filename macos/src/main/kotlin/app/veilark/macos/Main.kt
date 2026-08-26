package app.veilark.macos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.veilark.engine.TunnelEngineKind
import com.example.veilark.engine.TunnelStatus
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.session.VeilarkSession
import com.example.veilark.theme.VeilarkTheme
import com.example.veilark.ui.MacClipboard
import com.example.veilark.ui.Strings
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

fun main() = application {
  val session = remember { VeilarkSession.createDefault() }
  var tick by remember { mutableStateOf(0) }
  var windowVisible by remember { mutableStateOf(true) }
  val trayIcon = remember { BitmapPainter(trayBitmap().toComposeImageBitmap()) }
  fun refresh() {
    tick += 1
  }
  tick
  Tray(
    icon = trayIcon,
    tooltip = Strings.appName,
    onAction = { windowVisible = true },
    menu = {
      Item(Strings.appName, onClick = { windowVisible = true })
      Item(
        if (session.status == TunnelStatus.CONNECTED) Strings.disconnect else Strings.connect,
        onClick = {
          if (session.status == TunnelStatus.CONNECTED) {
            session.disconnect()
            refresh()
          }
        },
      )
      Item("Quit", onClick = ::exitApplication)
    },
  )
  if (windowVisible) {
    Window(
      onCloseRequest = { windowVisible = false },
      title = Strings.appName,
      state = rememberWindowState(width = 980.dp, height = 720.dp),
    ) {
      VeilarkTheme {
        CompositionLocalProvider(LocalClipboardManager provides MacClipboard) {
          MainScreen(session, tick, ::refresh)
        }
      }
    }
  }
}

private fun trayBitmap(): BufferedImage {
  val image = BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB)
  val graphics = image.createGraphics()
  graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
  graphics.color = Color(0x27, 0x5D, 0x8C)
  graphics.fillOval(1, 1, 16, 16)
  graphics.dispose()
  return image
}

@Composable
private fun MainScreen(session: VeilarkSession, tick: Int, refresh: () -> Unit) {
  tick
  val scope = rememberCoroutineScope()
  var importText by remember { mutableStateOf("") }
  val lastLog = session.logs.lastOrNull()?.message.orEmpty()
  Surface(modifier = Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Column(
        modifier = Modifier.weight(1.2f).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(Strings.appName, style = MaterialTheme.typography.headlineMedium)
        Text(
          when (session.status) {
            TunnelStatus.CONNECTED -> Strings.connected
            TunnelStatus.CONNECTING -> Strings.connecting
            TunnelStatus.FAILED -> session.statusDetail.ifBlank { Strings.failed }
            TunnelStatus.DISCONNECTED -> Strings.disconnected
          },
          style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = session.engine == TunnelEngineKind.TRUST_TUNNEL,
            onClick = { session.engine = TunnelEngineKind.TRUST_TUNNEL; refresh() },
            label = { Text(Strings.trustTunnel) },
          )
          FilterChip(
            selected = session.engine == TunnelEngineKind.SING_BOX,
            onClick = { session.engine = TunnelEngineKind.SING_BOX; refresh() },
            label = { Text(Strings.singBox) },
          )
        }
        Button(
          onClick = {
            scope.launch {
              if (session.status == TunnelStatus.CONNECTED) session.disconnect() else session.connect()
              refresh()
            }
          },
          enabled = !session.busy,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            when {
              session.busy && session.status == TunnelStatus.CONNECTING -> Strings.connecting
              session.status == TunnelStatus.CONNECTED -> Strings.disconnect
              else -> Strings.connect
            },
          )
        }
        if (lastLog.isNotBlank()) {
          Text(lastLog, style = MaterialTheme.typography.bodySmall)
        }
        if (!session.helperReady()) {
          Text(Strings.helperMissing, style = MaterialTheme.typography.bodySmall)
          TextButton(onClick = { session.installHelper(); refresh() }) {
            Text(Strings.installHelper)
          }
        }
        if (!session.enginesPresent()) {
          Text(Strings.enginesMissing, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = importText,
          onValueChange = { importText = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(Strings.importHint) },
          minLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              scope.launch {
                runCatching { session.importText(importText) }
                refresh()
              }
            },
            enabled = !session.busy,
          ) { Text(Strings.importAction) }
          TextButton(
            onClick = {
              val clipboard = MacClipboard.readPasteboard()
              if (!clipboard.isNullOrBlank()) {
                importText = clipboard
              } else {
                session.log("Буфер обмена пуст")
                refresh()
              }
            },
          ) { Text(Strings.paste) }
          TextButton(
            onClick = {
              val dialog = FileDialog(null as Frame?, Strings.file, FileDialog.LOAD)
              dialog.isVisible = true
              val file = dialog.file?.let { File(dialog.directory, it) }
              if (file != null) {
                scope.launch {
                  runCatching { session.importFile(file) }
                  refresh()
                }
              }
            },
          ) { Text(Strings.file) }
        }
      }
      Column(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(Strings.profiles, style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
          if (session.engine == TunnelEngineKind.SING_BOX) {
            items(session.singBoxEntries, key = { it.id }) { entry ->
              val selected = entry.id == session.selectedSingBoxId
              Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                SelectableRow(
                  title = entry.name,
                  subtitle = "${entry.nodes.size} · ${entry.origin.wireName}",
                  selected = selected,
                  onClick = { session.selectSingBox(entry.id); refresh() },
                )
                if (selected) {
                  Text(
                    Strings.servers,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
                  )
                  SelectableRow(
                    title = Strings.automatic,
                    selected = entry.selectedNodeTag == ProfileSelection.AUTOMATIC_TAG,
                    indent = true,
                    onClick = {
                      session.selectSingBox(entry.id, ProfileSelection.AUTOMATIC_TAG)
                      refresh()
                    },
                  )
                  entry.nodes.forEach { node ->
                    SelectableRow(
                      title = node.name,
                      subtitle = node.protocol,
                      selected = entry.selectedNodeTag == node.tag,
                      indent = true,
                      onClick = {
                        session.selectSingBox(entry.id, node.tag)
                        refresh()
                      },
                    )
                  }
                }
              }
            }
          } else {
            items(session.trustEntries, key = { it.id }) { entry ->
              SelectableRow(
                title = entry.name,
                selected = entry.id == session.selectedTrustId,
                onClick = { session.selectTrust(entry.id); refresh() },
              )
            }
          }
          if (
            (session.engine == TunnelEngineKind.SING_BOX && session.singBoxEntries.isEmpty()) ||
            (session.engine == TunnelEngineKind.TRUST_TUNNEL && session.trustEntries.isEmpty())
          ) {
            item { Text(Strings.noProfiles) }
          }
        }
        Text(Strings.log, style = MaterialTheme.typography.titleMedium)
        Column(
          modifier = Modifier.weight(0.8f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
          if (session.logs.isEmpty()) Text(Strings.technicalLogEmpty)
          session.logs.takeLast(40).forEach { entry ->
            Text(entry.message, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }
}


@Composable
private fun SelectableRow(
  title: String,
  subtitle: String? = null,
  selected: Boolean,
  indent: Boolean = false,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = if (indent) 12.dp else 0.dp, bottom = 4.dp)
      .clickable(onClick = onClick),
    color = if (selected) colors.primaryContainer else colors.surface,
    shape = RoundedCornerShape(8.dp),
  ) {
    Column(Modifier.padding(10.dp)) {
      Text(if (selected) "✓ $title" else title)
      if (!subtitle.isNullOrBlank()) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}
