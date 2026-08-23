package com.example.veilark.protocol

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Decodes `tt://` / `tt://?` deep links (TLV + Base64url) into `[endpoint]` TOML
 * for the macOS `trusttunnel_client` CLI.
 */
internal object TrustTunnelDeepLink {
  private const val MAX_VERSION = 1L

  fun toEndpointToml(link: String): String {
    var rest = link.trim()
    rest = when {
      rest.startsWith("tt://?", ignoreCase = true) -> rest.substring(6)
      rest.startsWith("tt://", ignoreCase = true) -> rest.substring(5)
      else -> error("Ссылка TrustTunnel должна начинаться с tt://")
    }
    if (rest.startsWith("?")) rest = rest.substring(1)
    require(rest.isNotBlank()) { "Ссылка TrustTunnel пуста" }
    if ('%' in rest) {
      rest = runCatching { URLDecoder.decode(rest, StandardCharsets.UTF_8) }.getOrDefault(rest)
    }
    val trimmed = rest.trim()
    if (trimmed.startsWith("[endpoint]")) {
      return remapAndroidKeys(trimmed)
    }
    val binary = decodeBase64Url(trimmed)
      ?: error("Ядро TrustTunnel вернуло некорректный профиль")
    val asText = binary.toString(Charsets.UTF_8).trim()
    if (asText.startsWith("[endpoint]")) {
      return remapAndroidKeys(asText)
    }
    return renderCliEndpoint(parseTlv(binary))
  }

  internal fun remapAndroidKeys(endpoint: String): String {
    var text = endpoint
    text = text.replace("\"http2\"", "\"http2\"").replace("\"http3\"", "\"http3\"")
    text = renameKey(text, "anti_dpi", "anti_dpi")
    text = renameKey(text, "has_ipv6", "has_ipv6")
    text = renameKey(text, "client_random_prefix", "client_random")
    return text
  }

  private fun renameKey(text: String, from: String, to: String): String {
    if (from == to) return text
    val fromPattern = Regex("""(?m)^(\s*)${Regex.escape(from)}(\s*=)""")
    if (!fromPattern.containsMatchIn(text)) return text
    val toPattern = Regex("""(?m)^\s*${Regex.escape(to)}\s*=""")
    if (toPattern.containsMatchIn(text)) {
      return text.replace(Regex("""(?m)^\s*${Regex.escape(from)}\s*=.*\n?"""), "")
    }
    return text.replace(fromPattern, "$1$to$2")
  }

  private data class Endpoint(
    var hostname: String? = null,
    val addresses: MutableList<String> = mutableListOf(),
    var customSni: String? = null,
    var hasIpv6: Boolean = true,
    var username: String? = null,
    var password: String? = null,
    var skipVerification: Boolean = false,
    var certificatePem: String? = null,
    var upstreamProtocol: String = "http2",
    var antiDpi: Boolean = false,
    var clientRandom: String? = null,
    var name: String? = null,
    var dnsUpstreams: List<String> = emptyList(),
  )

  private fun parseTlv(payload: ByteArray): Endpoint {
    val buf = Buffer(payload)
    val endpoint = Endpoint()
    while (buf.remaining() > 0) {
      val tag = buf.readVarint()
      val length = buf.readVarint().toInt()
      require(length >= 0 && length <= buf.remaining()) {
        "Ядро TrustTunnel вернуло некорректный профиль"
      }
      val value = buf.readBytes(length)
      when (tag) {
        0x00L -> {
          val version = Buffer(value).readVarint()
          require(version <= MAX_VERSION) {
            "Версия ссылки TrustTunnel не поддерживается"
          }
        }
        0x01L -> endpoint.hostname = value.decodeToString()
        0x02L -> endpoint.addresses += value.decodeToString()
        0x03L -> endpoint.customSni = value.decodeToString()
        0x04L -> endpoint.hasIpv6 = decodeBool(value)
        0x05L -> endpoint.username = value.decodeToString()
        0x06L -> endpoint.password = value.decodeToString()
        0x07L -> endpoint.skipVerification = decodeBool(value)
        0x08L -> endpoint.certificatePem = derToPem(value)
        0x09L -> endpoint.upstreamProtocol = decodeProtocol(value)
        0x0AL -> endpoint.antiDpi = decodeBool(value)
        0x0BL -> endpoint.clientRandom = value.decodeToString()
        0x0CL -> endpoint.name = value.decodeToString()
        0x0DL -> endpoint.dnsUpstreams = decodeStringArray(value)
        else -> Unit
      }
    }
    require(!endpoint.hostname.isNullOrBlank()) { "В ссылке TrustTunnel нет hostname" }
    require(endpoint.addresses.isNotEmpty()) { "В ссылке TrustTunnel нет addresses" }
    require(!endpoint.username.isNullOrBlank()) { "В ссылке TrustTunnel нет username" }
    require(!endpoint.password.isNullOrBlank()) { "В ссылке TrustTunnel нет password" }
    return endpoint
  }

  private fun renderCliEndpoint(endpoint: Endpoint): String = buildString {
    appendLine("[endpoint]")
    appendLine("hostname = ${tomlString(endpoint.hostname!!)}")
    appendLine("addresses = [${endpoint.addresses.joinToString(", ") { tomlString(it) }}]")
    endpoint.customSni?.takeIf { it.isNotBlank() }?.let {
      appendLine("custom_sni = ${tomlString(it)}")
    }
    appendLine("has_ipv6 = ${endpoint.hasIpv6}")
    appendLine("username = ${tomlString(endpoint.username!!)}")
    appendLine("password = ${tomlString(endpoint.password!!)}")
    endpoint.clientRandom?.takeIf { it.isNotBlank() }?.let {
      appendLine("client_random = ${tomlString(it)}")
    }
    appendLine("skip_verification = ${endpoint.skipVerification}")
    val pem = endpoint.certificatePem
    if (pem.isNullOrBlank()) {
      appendLine("certificate = \"\"")
    } else {
      appendLine("certificate = \"\"\"")
      appendLine(pem.trim())
      appendLine("\"\"\"")
    }
    appendLine("upstream_protocol = ${tomlString(endpoint.upstreamProtocol)}")
    appendLine("anti_dpi = ${endpoint.antiDpi}")
    if (endpoint.dnsUpstreams.isNotEmpty()) {
      appendLine(
        "dns_upstreams = [${endpoint.dnsUpstreams.joinToString(", ") { tomlString(it) }}]",
      )
    }
  }.trim()

  private fun decodeBool(value: ByteArray): Boolean {
    require(value.size == 1) { "Ядро TrustTunnel вернуло некорректный профиль" }
    return when (value[0].toInt()) {
      0 -> false
      1 -> true
      else -> error("Ядро TrustTunnel вернуло некорректный профиль")
    }
  }

  private fun decodeProtocol(value: ByteArray): String {
    require(value.size == 1) { "Ядро TrustTunnel вернуло некорректный профиль" }
    return when (value[0].toInt()) {
      1 -> "http2"
      2 -> "http3"
      else -> error("Ядро TrustTunnel вернуло некорректный профиль")
    }
  }

  private fun decodeStringArray(data: ByteArray): List<String> {
    val buf = Buffer(data)
    val values = mutableListOf<String>()
    while (buf.remaining() > 0) {
      val length = buf.readVarint().toInt()
      values += buf.readBytes(length).decodeToString()
    }
    return values
  }

  private fun derToPem(data: ByteArray): String {
    val certs = runCatching { splitDerCerts(data) }.getOrElse { listOf(data) }
    return certs.joinToString("\n") { der ->
      val body = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
      "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"
    }
  }

  private fun splitDerCerts(data: ByteArray): List<ByteArray> {
    val certs = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < data.size) {
      require(data[offset].toInt() and 0xFF == 0x30) {
        "Ядро TrustTunnel вернуло некорректный профиль"
      }
      val (length, headerEnd) = readAsn1Length(data, offset + 1)
      val end = headerEnd + length
      require(end <= data.size) { "Ядро TrustTunnel вернуло некорректный профиль" }
      certs += data.copyOfRange(offset, end)
      offset = end
    }
    require(certs.isNotEmpty()) { "Ядро TrustTunnel вернуло некорректный профиль" }
    return certs
  }

  private fun readAsn1Length(data: ByteArray, offset: Int): Pair<Int, Int> {
    require(offset < data.size) { "Ядро TrustTunnel вернуло некорректный профиль" }
    val first = data[offset].toInt() and 0xFF
    if (first < 0x80) return first to (offset + 1)
    val width = first and 0x7F
    require(width in 1..4 && offset + 1 + width <= data.size) {
      "Ядро TrustTunnel вернуло некорректный профиль"
    }
    var length = 0
    for (i in 0 until width) {
      length = (length shl 8) or (data[offset + 1 + i].toInt() and 0xFF)
    }
    return length to (offset + 1 + width)
  }

  private fun decodeBase64Url(value: String): ByteArray? {
    val normalized = value.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/')
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
  }

  private fun tomlString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private class Buffer(private val bytes: ByteArray) {
    var offset = 0
    fun remaining(): Int = bytes.size - offset

    fun readVarint(): Long {
      require(offset < bytes.size) { "Ядро TrustTunnel вернуло некорректный профиль" }
      val first = bytes[offset].toInt() and 0xFF
      val prefix = first ushr 6
      val size = 1 shl prefix
      require(offset + size <= bytes.size) { "Ядро TrustTunnel вернуло некорректный профиль" }
      var value = (first and 0x3F).toLong()
      for (i in 1 until size) {
        value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF).toLong()
      }
      offset += size
      return value
    }

    fun readBytes(length: Int): ByteArray {
      require(length >= 0 && offset + length <= bytes.size) {
        "Ядро TrustTunnel вернуло некорректный профиль"
      }
      val slice = bytes.copyOfRange(offset, offset + length)
      offset += length
      return slice
    }
  }
}
