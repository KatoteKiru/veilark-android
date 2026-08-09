package com.example.veilark.diagnostics

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

data class TechnicalLogEntry(
  val timestamp: Long,
  val level: String,
  val component: String,
  val message: String,
)

object TechnicalLogStore {
  private const val MAX_ENTRIES = 600
  private const val MAX_FILE_BYTES = 512 * 1024
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutableEntries = MutableStateFlow<List<TechnicalLogEntry>>(emptyList())
  val entries = mutableEntries.asStateFlow()
  private val lock = Any()
  @Volatile
  private var logFile: File? = null

  fun initialize(context: Context) {
    if (logFile != null) return
    synchronized(lock) {
      if (logFile != null) return
      logFile = File(context.filesDir, "technical-events.log")
      mutableEntries.value = logFile
        ?.takeIf(File::isFile)
        ?.readLines(Charsets.UTF_8)
        ?.takeLast(MAX_ENTRIES)
        ?.mapNotNull(::decode)
        .orEmpty()
    }
    info("APP", "Veilark запущен")
  }

  fun info(component: String, message: String) = append("INFO", component, message)

  fun warning(component: String, message: String) = append("WARN", component, message)

  fun error(component: String, message: String) = append("ERROR", component, message)

  fun clear() {
    synchronized(lock) {
      mutableEntries.value = emptyList()
    }
    scope.launch { logFile?.delete() }
  }

  private fun append(level: String, component: String, message: String) {
    val entry = TechnicalLogEntry(
      timestamp = Instant.now().toEpochMilli(),
      level = level,
      component = safe(component, 32),
      message = safe(message, 800),
    )
    synchronized(lock) {
      val updated = (mutableEntries.value + entry).takeLast(MAX_ENTRIES)
      if (updated.takeLast(2).let { it.size == 2 && sameEvent(it[0], it[1]) }) return
      mutableEntries.value = updated
    }
    scope.launch {
      val file = logFile ?: return@launch
      synchronized(lock) {
        if (file.length() > MAX_FILE_BYTES) {
          file.writeText(mutableEntries.value.joinToString("\n", postfix = "\n", transform = ::encode))
        } else {
          file.appendText("${encode(entry)}\n", Charsets.UTF_8)
        }
      }
    }
  }

  private fun sameEvent(first: TechnicalLogEntry, second: TechnicalLogEntry): Boolean =
    first.level == second.level &&
      first.component == second.component &&
      first.message == second.message &&
      second.timestamp - first.timestamp < 1_500

  private fun safe(value: String, limit: Int): String = value
    .replace(Regex("""\u001B\[[;?\d]*[ -/]*[@-~]"""), "")
    .replace(
      Regex("""(?i)\b(tt|vless|trojan|hysteria2?)://\S+"""),
      "<profile-link-redacted>",
    )
    .replace(
      Regex("""(?i)(https?://)[^/\s:@]+:[^@\s/]+@"""),
      "$1<credentials-redacted>@",
    )
    .replace(
      Regex("""(?i)(password|passwd|token|secret|private_key|uuid)\s*[=:]\s*["']?[^,\s"']+"""),
      "$1=<redacted>",
    )
    .replace(
      Regex("""(?i)([?&](?:token|key|password|auth)=)[^&#\s]+"""),
      "$1<redacted>",
    )
    .replace(
      Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b"""),
      "<uuid-redacted>",
    )
    .replace('\t', ' ')
    .replace('\r', ' ')
    .replace('\n', ' ')
    .trim()
    .take(limit)

  private fun encode(entry: TechnicalLogEntry): String =
    "${entry.timestamp}\t${entry.level}\t${entry.component}\t${entry.message}"

  private fun decode(line: String): TechnicalLogEntry? {
    val fields = line.split('\t', limit = 4)
    if (fields.size != 4) return null
    return TechnicalLogEntry(
      timestamp = fields[0].toLongOrNull() ?: return null,
      level = fields[1],
      component = fields[2],
      message = fields[3],
    )
  }
}
