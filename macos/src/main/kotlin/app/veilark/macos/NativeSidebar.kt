package app.veilark.macos

import java.io.File
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Missing/incompatible native UI must never prevent the VPN client from opening. */
internal object NativeSidebar {
  private val loaded: Boolean by lazy {
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

  fun install(title: String, labels: Array<String>): Long {
    if (!loaded || labels.size != 8) return 0
    return runCatching {
      val logo = ByteArrayOutputStream().use { bytes ->
        check(ImageIO.write(brandBitmap(64, darkTheme = false, tray = true), "png", bytes))
        bytes.toByteArray()
      }
      MacNativeChrome.install(title, labels, logo)
    }.getOrElse {
      StartupDiagnostics.record(it, "native-chrome-install")
      0
    }
  }

  fun update(handle: Long, index: Int, status: String) {
    if (handle == 0L) return
    runCatching { MacNativeChrome.update(handle, index, status) }
      .onFailure { StartupDiagnostics.record(it, "native-chrome-update") }
  }

  fun postUpdateNotice(title: String, body: String, build: Int): Boolean =
    loaded && runCatching { MacNativeChrome.postUpdateNotice(title, body, build) }.getOrDefault(false)

  fun remove(handle: Long) {
    if (handle == 0L) return
    runCatching { MacNativeChrome.remove(handle) }
      .onFailure { StartupDiagnostics.record(it, "native-chrome-remove") }
  }
}
