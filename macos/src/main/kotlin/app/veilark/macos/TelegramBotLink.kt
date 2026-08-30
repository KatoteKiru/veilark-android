package app.veilark.macos

import java.awt.Desktop
import java.net.URI

internal object TelegramBotLink {
  const val DEFAULT_URL = "https://t.me/senyavpn_bot?start=client_macos"
  private const val PROPERTY_NAME = "veilark.telegramBotUrl"
  private const val ENVIRONMENT_NAME = "VEILARK_TELEGRAM_BOT_URL"
  private val allowedHosts = setOf("t.me", "telegram.me")

  fun configuredUrl(): String =
    System.getProperty(PROPERTY_NAME)?.takeIf(String::isNotBlank)
      ?: System.getenv(ENVIRONMENT_NAME)?.takeIf(String::isNotBlank)
      ?: DEFAULT_URL

  fun validate(raw: String): URI? {
    val candidate = raw.trim().takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return uri.takeIf {
      it.scheme.equals("https", ignoreCase = true) &&
        host in allowedHosts &&
        it.userInfo == null
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
