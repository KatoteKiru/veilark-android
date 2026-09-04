package app.veilark.macos

import java.awt.Desktop
import java.net.URI

internal object VeilarkWebLink {
  const val DEFAULT_URL = "https://sub.senyasenyavski.uk/tma/"
  private const val PROPERTY_NAME = "veilark.webAppUrl"
  private const val ENVIRONMENT_NAME = "VEILARK_WEB_APP_URL"

  fun configuredUrl(): String =
    System.getProperty(PROPERTY_NAME)?.takeIf(String::isNotBlank)
      ?: System.getenv(ENVIRONMENT_NAME)?.takeIf(String::isNotBlank)
      ?: DEFAULT_URL

  fun validate(raw: String): URI? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    return uri.takeIf {
      it.scheme.equals("https", ignoreCase = true) &&
        it.host.equals("sub.senyasenyavski.uk", ignoreCase = true) &&
        it.userInfo == null &&
        it.port == -1 &&
        it.path == "/tma/" &&
        it.rawQuery == null &&
        it.fragment == null
    }
  }

  fun openConfigured(): Boolean {
    val uri = validate(configuredUrl()) ?: return false
    return runCatching {
      check(Desktop.isDesktopSupported())
      val desktop = Desktop.getDesktop()
      check(desktop.isSupported(Desktop.Action.BROWSE))
      desktop.browse(uri)
    }.isSuccess
  }
}
