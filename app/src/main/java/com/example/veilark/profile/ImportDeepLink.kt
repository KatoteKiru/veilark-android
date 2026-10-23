package com.example.veilark.profile

import android.content.Intent
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Validates Veilark-owned import intents before they reach the subscription parser.
 *
 * The app accepts HTTPS subscriptions, native TrustTunnel links and the canonical
 * protected enrollment envelope. Keeping this
 * boundary separate from the parser makes exported intent handling easy to test and
 * prevents arbitrary URI schemes, userinfo, or oversized intent extras from entering
 * the import flow.
 */
internal object ImportDeepLink {
  private const val SCHEME = "veilark"
  private const val HOST = "import"
  private const val PARAM = "url"
  private const val OPERATOR_PROFILE_ORIGIN = "https://nl2.senyasenyavski.uk:2096/veilark/"
  private const val MAX_INTENT_URI_LENGTH = 32 * 1024 // a fully percent-encoded 8192-char payload is ~24 KiB
  internal const val MAX_PAYLOAD_LENGTH = 8192 // matches the Mini App isVeilarkImportTarget limit; tt://? payloads may embed certificates

  fun parse(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    return intent.data?.toString()?.let { raw ->
      parseUri(raw)?.let { target ->
        // Keep the opaque representation in the import form; dispatch unwraps it later.
        if (URI(raw).rawQuery?.let { it.startsWith("code=") || it.startsWith("t=") } == true) raw else target
      }
    }
  }

  /** Pure URI boundary used by the Android intent adapter and local regression tests. */
  internal fun parseUri(raw: String): String? {
    // Preserve the complete bearer envelope for the dedicated local verifier;
    // never unwrap its key into a URL or treat it as a legacy import token.
    if (EnrollmentV2Store.isCanonicalLink(raw)) return raw
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
    val items = query.split('&').filter(String::isNotEmpty)
    if (items.size != 1) return null
    val item = items.single()
    val equal = item.indexOf('=')
    if (equal < 0) return null
    return when (decode(item.substring(0, equal))) {
      PARAM -> decode(item.substring(equal + 1))
      "code" -> decodeImportCode(item.substring(equal + 1))
      "t" -> decodeCompactToken(item.substring(equal + 1))
      else -> null
    }
  }

  // A display-safe code, not encryption or network anonymity. Keep HTTPS validation
  // after decoding; the code must never authorize a new protocol or arbitrary input.
  private fun decodeImportCode(code: String): String? = runCatching {
    require(code.isNotEmpty() && code.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' })
    val bytes = Base64.getUrlDecoder().decode(code)
    require(bytes.size <= MAX_PAYLOAD_LENGTH)
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == code)
    val decoded = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    require(URI(decoded).scheme.equals("https", ignoreCase = true))
    decoded
  }.getOrNull()

  // An opaque lookup token, NOT encryption. Restrict expansion to the existing
  // operator origin; input cannot choose a host, path, port or protocol.
  private fun decodeCompactToken(token: String): String? = runCatching {
    require(token.length == 43 && token.all {
      it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
    })
    val bytes = Base64.getUrlDecoder().decode(token)
    require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == token)
    OPERATOR_PROFILE_ORIGIN + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".json"
  }.getOrNull()

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
    if (EnrollmentV2Store.isCanonicalLink(value)) return value
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
