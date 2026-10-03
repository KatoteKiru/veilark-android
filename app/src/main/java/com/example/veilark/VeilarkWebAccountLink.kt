package com.example.veilark

import java.net.URI

/** Strict allowlist for the public Veilark web cabinet opened outside the app. */
internal object VeilarkWebAccountLink {
  fun validate(raw: String): URI? {
    val candidate = raw.trim().takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    return uri.takeIf {
      it.scheme.equals("https", ignoreCase = true) &&
        it.host.equals(EXPECTED_HOST, ignoreCase = true) &&
        it.port == -1 &&
        it.userInfo == null &&
        it.path == EXPECTED_PATH &&
        it.rawQuery == null &&
        it.rawFragment == null
    }
  }

  private const val EXPECTED_HOST = "sub.senyasenyavski.uk"
  private const val EXPECTED_PATH = "/tma/"
}
