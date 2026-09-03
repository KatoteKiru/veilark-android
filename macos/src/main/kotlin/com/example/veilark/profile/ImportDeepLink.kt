package com.example.veilark.profile

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Validates `veilark://import?url=...` deep links before they reach the import flow.
 *
 * Only the two import payload families the client can process are accepted: an HTTPS
 * subscription URL or a protocol-native TrustTunnel link (`tt://<host>...` or the
 * query-only `tt://?<payload>` form emitted by `trusttunnel_endpoint -f deeplink`).
 * The rules mirror the Android and Windows clients so that one link works everywhere.
 *
 * The inner URL is a bearer credential: it is never logged and every failure collapses
 * to `null` instead of surfacing the value through an exception message.
 */
internal object ImportDeepLink {
  private const val SCHEME = "veilark"
  private const val HOST = "import"
  private const val PARAM = "url"
  private const val MAX_LINK_LENGTH = 32 * 1024 // a fully percent-encoded 8192-char payload is ~24 KiB
  internal const val MAX_PAYLOAD_LENGTH = 8192 // matches the Mini App isVeilarkImportTarget limit; tt://? payloads may embed certificates

  fun parse(uri: URI?): String? = uri?.toString()?.let(::parseUri)

  fun parseUri(raw: String): String? = parseEnvelope(raw)?.let(::validatePayload)

  private fun parseEnvelope(raw: String): String? {
    if (raw.length > MAX_LINK_LENGTH) return null
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
      value.startsWith("tt://?", ignoreCase = true) &&
        payload.scheme.equals("tt", ignoreCase = true) &&
        payload.rawAuthority == null &&
        payload.rawPath.isNullOrEmpty() &&
        payload.rawQuery?.isNotBlank() == true &&
        payload.fragment == null -> value
      else -> null
    }
  }

  private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
      .getOrElse { "" }
}
