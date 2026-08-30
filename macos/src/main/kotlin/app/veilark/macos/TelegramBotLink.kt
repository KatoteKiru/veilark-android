package app.veilark.macos

import java.awt.Desktop
import java.net.URI

internal object TelegramBotLink {
  const val DEFAULT_URL = "https://t.me/senyavpn_bot?start=client_macos"
  const val DEFAULT_SUPPORT_URL = "https://t.me/senyavpn_bot?start=support"
  private const val BOT_PATH = "/senyavpn_bot"
  private val allowedHosts = setOf("t.me", "telegram.me")

  enum class Destination(
    internal val startPayload: String,
    internal val propertyName: String,
    internal val environmentName: String,
    internal val defaultUrl: String,
  ) {
    SUBSCRIPTION(
      startPayload = "client_macos",
      propertyName = "veilark.telegramBotUrl",
      environmentName = "VEILARK_TELEGRAM_BOT_URL",
      defaultUrl = DEFAULT_URL,
    ),
    SUPPORT(
      startPayload = "support",
      propertyName = "veilark.telegramSupportUrl",
      environmentName = "VEILARK_TELEGRAM_SUPPORT_URL",
      defaultUrl = DEFAULT_SUPPORT_URL,
    ),
  }

  fun configuredUrl(destination: Destination = Destination.SUBSCRIPTION): String =
    System.getProperty(destination.propertyName)?.takeIf(String::isNotBlank)
      ?: System.getenv(destination.environmentName)?.takeIf(String::isNotBlank)
      ?: destination.defaultUrl

  fun validate(raw: String, destination: Destination = Destination.SUBSCRIPTION): URI? {
    val candidate = raw.trim().takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return uri.takeIf {
      it.scheme.equals("https", ignoreCase = true) &&
        host in allowedHosts &&
        it.userInfo == null &&
        it.port == -1 &&
        it.path == BOT_PATH &&
        it.rawQuery == "start=${destination.startPayload}" &&
        it.fragment == null
    }
  }

  fun openConfigured(destination: Destination = Destination.SUBSCRIPTION): Boolean {
    val uri = validate(configuredUrl(destination), destination) ?: return false
    return runCatching {
      check(Desktop.isDesktopSupported())
      val desktop = Desktop.getDesktop()
      check(desktop.isSupported(Desktop.Action.BROWSE))
      desktop.browse(uri)
    }.isSuccess
  }
}
