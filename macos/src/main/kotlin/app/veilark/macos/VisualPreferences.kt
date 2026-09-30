package app.veilark.macos

import androidx.compose.runtime.compositionLocalOf
import java.util.concurrent.TimeUnit

internal data class VisualPreferences(
  val reduceMotion: Boolean = true,
  val reduceTransparency: Boolean = true,
  val increaseContrast: Boolean = false,
)
internal val LocalVisualPreferences = compositionLocalOf { VisualPreferences() }

/** Decodes the AppKit mask (1 motion, 2 transparency, 4 contrast); negative means unknown. */
internal fun visualPreferencesFromMask(mask: Int): VisualPreferences? =
  if (mask !in 0..7) null else VisualPreferences(
    reduceMotion = mask and MacNativeChrome.REDUCE_MOTION != 0,
    reduceTransparency = mask and MacNativeChrome.REDUCE_TRANSPARENCY != 0,
    increaseContrast = mask and MacNativeChrome.INCREASE_CONTRAST != 0,
  )

/**
 * Read off the UI thread. Primary source: NSWorkspace accessibility display options via the
 * native bridge, which also delivers live changes. Fallback (bridge missing or AppKit busy):
 * `defaults read`; unknown accessibility state uses the quiet fallback.
 */
internal fun readVisualPreferences(): VisualPreferences {
  if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return VisualPreferences()
  NativeSidebar.displayPreferences()?.let { return it }
  visualPreferencesFromMask(MacNativeChrome.lastDisplayMask())?.let { return it }
  return readVisualPreferencesFromDefaults()
}

private fun readVisualPreferencesFromDefaults(): VisualPreferences {
  fun preference(key: String, unknown: Boolean): Boolean = runCatching {
    val process = ProcessBuilder("/usr/bin/defaults", "read", "com.apple.universalaccess", key)
      .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if (!process.waitFor(1, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      unknown
    } else {
      val value = process.inputStream.bufferedReader().use { it.readText().trim() }
      // An absent preference is the macOS default (off); an invalid value is conservative.
      if (process.exitValue() != 0) false else when (value.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> unknown
      }
    }
  }.getOrDefault(unknown)
  return VisualPreferences(
    reduceMotion = preference("reduceMotion", unknown = true),
    reduceTransparency = preference("reduceTransparency", unknown = true),
    increaseContrast = preference("increaseContrast", unknown = false),
  )
}
