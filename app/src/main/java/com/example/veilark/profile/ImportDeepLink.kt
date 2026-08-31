package com.example.veilark.profile

import android.content.Intent
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Validates Veilark-owned import intents before they reach the subscription parser.
 *
 * The app deliberately accepts only the two import payload families it can process:
 * an HTTPS subscription URL or a protocol-native TrustTunnel link.  Keeping this
 * boundary separate from the parser makes exported intent handling easy to test and
 * prevents arbitrary URI schemes, userinfo, or oversized intent extras from entering
 * the import flow.
 */
internal object ImportDeepLink {
  private const val SCHEME = "veilark"
  private const val HOST = "import"
  private const val PARAM = "url"
  private const val MAX_INTENT_URI_LENGTH = 16 * 1024
  private const val MAX_PAYLOAD_LENGTH = 8 * 1024

  fun parse(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    return intent.data?.toString()?.let(::parseUri)
  }

  /** Pure URI boundary used by the Android intent adapter and local regression tests. */
  internal fun parseUri(raw: String): String? {
    if (raw.length > MAX_INTENT_URI_LENGTH) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!uri.scheme.equals(SCHEME, ignoreCase = true) ||
      !uri.host.equals(HOST, ignoreCase = true) ||
      (uri.path != null && uri.path != "" && uri.path != "/") ||
      uri.fragment != null ||
      uri.userInfo != null ||
      uri.port != -1
    ) {
      return null
    }
    val query = uri.rawQuery ?: return null
    val values = query.split('&')
      .filter(String::isNotEmpty)
      .mapNotNull { item ->
        val equal = item.indexOf('=')
        if (equal < 0) return@mapNotNull null
        val key = decode(item.substring(0, equal))
        if (key != PARAM) return@mapNotNull null
        decode(item.substring(equal + 1))
      }
    if (values.size != 1 || query.split('&').count(String::isNotEmpty) != 1) return null
    return validatePayload(values.single())
  }

  internal fun validatePayload(value: String): String? {
    if (value.isEmpty() || value.length > MAX_PAYLOAD_LENGTH ||
      value.any { it == '\u0000' || it == '\r' || it == '\n' || it.isISOControl() }
    ) {
      return null
    }
    val payload = runCatching { URI(value) }.getOrNull() ?: return null
    return when {
      payload.scheme.equals("https", ignoreCase = true) &&
        payload.host?.isNotBlank() == true &&
        payload.userInfo == null &&
        (payload.port == -1 || payload.port in 1..65535) &&
        payload.fragment == null -> value
      value.startsWith("tt://", ignoreCase = true) &&
        payload.scheme.equals("tt", ignoreCase = true) &&
        payload.host?.isNotBlank() == true &&
        payload.userInfo == null &&
        payload.fragment == null -> value
      else -> null
    }
  }

  private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
      .getOrElse { "" }
}
