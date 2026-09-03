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
  private const val MAX_INTENT_URI_LENGTH = 32 * 1024 // a fully percent-encoded 8192-char payload is ~24 KiB
  internal const val MAX_PAYLOAD_LENGTH = 8192 // matches the Mini App isVeilarkImportTarget limit; tt://? payloads may embed certificates

  fun parse(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    return intent.data?.toString()?.let(::parseUri)
  }

  /** Pure URI boundary used by the Android intent adapter and local regression tests. */
  internal fun parseUri(raw: String): String? {
    return parseEnvelope(raw)?.let(::validatePayload)
  }

  private fun parseEnvelope(raw: String): String? {
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
    return values.single()
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
      // `trusttunnel_endpoint -f deeplink` emits the query-only form `tt://?<payload>`:
      // no authority, no path, the whole profile travels in the query component.
      value.startsWith("tt://?", ignoreCase = true) &&
        payload.scheme.equals("tt", ignoreCase = true) &&
        payload.rawAuthority == null &&
        payload.rawPath.isNullOrEmpty() &&
        payload.rawQuery?.isNotBlank() == true &&
        payload.fragment == null -> value
      else -> null
    }
  }

  /**
   * Normalizes a QR result before it enters the shared import pipeline. QR scanners return the
   * payload verbatim, so a Veilark import envelope must be unwrapped and protocol schemes must
   * be canonicalized for parsers that use a case-sensitive prefix check.
   */
  internal fun parseQrPayload(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty() || value.length > MAX_PAYLOAD_LENGTH ||
      value.any { it == '\u0000' || it == '\r' || it == '\n' || it.isISOControl() }
    ) {
      return null
    }
    val unwrapped = if (value.startsWith("$SCHEME://", ignoreCase = true)) {
      parseEnvelope(value) ?: return null
    } else {
      value
    }
    val separator = unwrapped.indexOf("://")
    if (separator <= 0) return null
    val scheme = unwrapped.substring(0, separator).lowercase()
    return when (scheme) {
      "https" -> validatePayload(unwrapped)
      "tt", "vless", "vmess", "trojan",
      "hysteria2", "hy2", "tuic", "anytls" ->
        scheme + unwrapped.substring(separator)
      "ss", "shadowsocks" -> "ss://" + unwrapped.substring(separator + "://".length)
      else -> null
    }
  }

  private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
      .getOrElse { "" }
}
