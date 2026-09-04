package app.veilark.macos

import androidx.compose.runtime.compositionLocalOf
import java.util.concurrent.TimeUnit

internal data class VisualPreferences(val reduceMotion: Boolean = true, val reduceTransparency: Boolean = true)
internal val LocalVisualPreferences = compositionLocalOf { VisualPreferences() }

/** Read once off the UI thread; unknown accessibility state uses the quiet fallback. */
internal fun readVisualPreferences(): VisualPreferences {
  if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return VisualPreferences()
  fun preference(key: String): Boolean = runCatching {
    val process = ProcessBuilder("/usr/bin/defaults", "read", "com.apple.universalaccess", key)
      .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if (!process.waitFor(1, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      true
    } else {
      val value = process.inputStream.bufferedReader().use { it.readText().trim() }
      // An absent preference is the macOS default (off); an invalid value is conservative.
      if (process.exitValue() != 0) false else when (value.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> true
      }
    }
  }.getOrDefault(true)
  return VisualPreferences(preference("reduceMotion"), preference("reduceTransparency"))
}
