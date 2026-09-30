package app.veilark.macos

import java.io.File
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Snapshot of the attached AppKit chrome, read back for layout and CI evidence. */
internal data class NativeChromeState(
  /** 0 opaque (Reduce Transparency), 1 NSVisualEffectView, 2 NSGlassEffectView. */
  val material: Int,
  /** 0 no toolbar, 1 unified NSToolbar, 2 unified NSToolbar with glass-bezel buttons. */
  val toolbar: Int,
  /** 1 native window handle, 2 title plus exact frame, 3 only visible window with the title. */
  val matchedBy: Int,
  /** Points hidden by the (transparent) titlebar/toolbar above the Compose content. */
  val topInset: Int,
  val width: Int,
  val height: Int,
) {
  companion object {
    fun from(values: IntArray?): NativeChromeState? =
      values?.takeIf { it.size == 6 && it[0] >= 0 }?.let { NativeChromeState(it[0], it[1], it[2], it[3], it[4], it[5]) }
  }
}

/** Missing/incompatible native UI must never prevent the VPN client from opening. */
internal object NativeSidebar {
  const val LABEL_COUNT = 9

  val loaded: Boolean by lazy {
    if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) false
    else runCatching {
      val root = System.getProperty("compose.application.resources.dir")
      val library = if (root.isNullOrBlank()) File("packaging/common/libveilark-chrome.dylib")
        else File(root, "libveilark-chrome.dylib")
      System.load(library.absolutePath)
      true
    }.getOrElse {
      StartupDiagnostics.record(it, "native-chrome-load")
      false
    }
  }

  /**
   * Attaches the native sidebar and toolbar to one specific window. [windowHandle] is the
   * NSWindow pointer reported by Compose (0 when unknown); [bounds] is the AWT frame
   * (x, y, width, height) used to disambiguate windows sharing a title.
   */
  fun install(
    windowHandle: Long,
    title: String,
    bounds: IntArray,
    unifiedTitlebar: Boolean,
    labels: Array<String>,
  ): Long {
    if (!loaded || labels.size != LABEL_COUNT || bounds.size != 4) return 0
    return runCatching {
      val logo = ByteArrayOutputStream().use { bytes ->
        check(ImageIO.write(brandBitmap(64, darkTheme = false, tray = true), "png", bytes))
        bytes.toByteArray()
      }
      MacNativeChrome.install(windowHandle, title, bounds, unifiedTitlebar, labels, logo)
    }.getOrElse {
      StartupDiagnostics.record(it, "native-chrome-install")
      0
    }
  }

  fun update(handle: Long, index: Int, status: String, action: String = "", actionEnabled: Boolean = true) {
    if (handle == 0L) return
    runCatching { MacNativeChrome.update(handle, index, status, action, actionEnabled) }
      .onFailure { StartupDiagnostics.record(it, "native-chrome-update") }
  }

  fun state(handle: Long): NativeChromeState? {
    if (!loaded || handle == 0L) return null
    return runCatching { NativeChromeState.from(MacNativeChrome.nativeState(handle)) }
      .onFailure { StartupDiagnostics.record(it, "native-chrome-state") }
      .getOrNull()
  }

  /** Live AppKit accessibility display options; null means "use the fallback reader". */
  fun displayPreferences(): VisualPreferences? {
    if (!loaded) return null
    return runCatching { visualPreferencesFromMask(MacNativeChrome.observeDisplayPreferences()) }
      .onFailure { StartupDiagnostics.record(it, "native-display-preferences") }
      .getOrNull()
  }

  /**
   * Presents a native NSAlert sheet on the window owning [handle]. [onAnswer] receives
   * true/false on the AppKit thread. Returns false (and never calls back) when the caller
   * must show its Compose dialog; [onUnavailable] runs if AppKit declines later.
   */
  fun confirm(
    handle: Long,
    title: String,
    message: String,
    confirmTitle: String,
    cancelTitle: String,
    destructive: Boolean,
    onUnavailable: () -> Unit,
    onAnswer: (Boolean) -> Unit,
  ): Boolean {
    if (!loaded || handle == 0L) return false
    val id = MacNativeChrome.registerConfirmation { result ->
      when (result) {
        1 -> onAnswer(true)
        0 -> onAnswer(false)
        else -> onUnavailable()
      }
    }
    val shown = runCatching {
      MacNativeChrome.confirm(handle, id, title, message, confirmTitle, cancelTitle, destructive)
    }.onFailure { StartupDiagnostics.record(it, "native-confirm") }.getOrDefault(false)
    if (!shown) MacNativeChrome.cancelConfirmation(id)
    return shown
  }

  /** "26.5" style SDK of the running main executable, or null when unknown. */
  fun programSdk(): String? {
    if (!loaded) return null
    return runCatching { formatPackedVersion(MacNativeChrome.programSdkVersion()) }.getOrNull()
  }

  /**
   * CI evidence only: when VEILARK_NATIVE_CHROME_REPORT names a file, record the running
   * executable's SDK and the attached chrome. Contains no profile or network data.
   */
  fun writeReportIfRequested(handle: Long) {
    val path = System.getenv("VEILARK_NATIVE_CHROME_REPORT")?.takeIf(String::isNotBlank) ?: return
    runCatching {
      File(path).writeText(nativeChromeReport(programSdk(), state(handle)))
    }.onFailure { StartupDiagnostics.record(it, "native-chrome-report") }
  }

  fun postUpdateNotice(title: String, body: String, build: Int): Boolean =
    loaded && runCatching { MacNativeChrome.postUpdateNotice(title, body, build) }.getOrDefault(false)

  fun remove(handle: Long) {
    if (handle == 0L) return
    runCatching { MacNativeChrome.remove(handle) }
      .onFailure { StartupDiagnostics.record(it, "native-chrome-remove") }
  }
}

/** dyld packs versions as major << 16 | minor << 8 | patch. */
internal fun formatPackedVersion(packed: Int): String? {
  if (packed <= 0) return null
  val major = packed ushr 16
  val minor = (packed shr 8) and 0xff
  val patch = packed and 0xff
  return if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
}

internal fun nativeChromeReport(programSdk: String?, state: NativeChromeState?): String = buildString {
  append("programSdk=").append(programSdk ?: "unknown").append('\n')
  append("material=").append(state?.material ?: -1).append('\n')
  append("toolbar=").append(state?.toolbar ?: -1).append('\n')
  append("matchedBy=").append(state?.matchedBy ?: 0).append('\n')
  append("topInset=").append(state?.topInset ?: 0).append('\n')
}
