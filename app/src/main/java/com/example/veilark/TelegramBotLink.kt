package com.example.veilark

import java.net.URI

internal object TelegramBotLink {
  private val allowedHosts = setOf("t.me", "telegram.me")

  fun validate(raw: String): URI? {
    val candidate = raw.trim().takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    return uri.takeIf {
      it.scheme.equals("https", ignoreCase = true) &&
        host in allowedHosts &&
        it.port == -1 &&
        it.userInfo == null &&
        it.path == EXPECTED_PATH &&
        it.rawQuery == EXPECTED_QUERY &&
        it.rawFragment == null
    }
  }

  private const val EXPECTED_PATH = "/senyavpn_bot"
  private const val EXPECTED_QUERY = "start=client_android"
}
