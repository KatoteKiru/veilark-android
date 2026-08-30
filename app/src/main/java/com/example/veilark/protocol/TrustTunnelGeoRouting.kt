package com.example.veilark.protocol

import android.content.Context
import com.example.veilark.profile.ProfileSelection
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Loads the pinned IPv4/IPv6 prefixes used by the TrustTunnel general-mode
 * exclusion list. TrustTunnel's native core accepts CIDRs in its top-level
 * `exclusions` setting and applies them as direct bypasses; the Android TUN
 * listener must remain a full tunnel so the core can keep endpoint and LAN
 * handling intact.
 */
object TrustTunnelGeoRouting {
  private const val ASSET = "rules/geoip-ru.json"
  internal const val EXPECTED_CIDR_COUNT = 10_859
  internal const val EXPECTED_TOKEN_SHA256 =
    "e8f53102eab91db9fb6329d79a1dd528564818dfe603018a9261138334bcbc1d"

  @Volatile
  private var cached: List<String>? = null

  fun ruCidrs(context: Context): List<String> {
    cached?.let { return it }
    return synchronized(this) {
      cached ?: context.assets.open(ASSET).bufferedReader().use { reader ->
        parseRuCidrs(reader.readText()).also { cidrs ->
          check(cidrs.size == EXPECTED_CIDR_COUNT) {
            "TrustTunnel geoip asset has an unexpected CIDR count"
          }
          check(sha256Token(cidrs) == EXPECTED_TOKEN_SHA256) {
            "TrustTunnel geoip asset integrity check failed"
          }
        }
      }.also { cached = it }
    }
  }

  fun currentDirectCidrs(context: Context): List<String> {
    val mode = context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE)
      .getString("routing_mode", ProfileSelection.ROUTING_ALL)
      ?: ProfileSelection.ROUTING_ALL
    return if (mode == ProfileSelection.ROUTING_RU_DIRECT) ruCidrs(context) else emptyList()
  }

  /** TrustTunnel starts as a full tunnel; direct CIDRs are applied after CONNECTED. */
  fun startupConfig(config: String): String =
    TrustTunnelProfile.withDirectCidrs(config, emptyList())

  internal fun parseRuCidrs(payload: String): List<String> {
    val rules = JSONObject(payload).optJSONArray("rules")
      ?: error("TrustTunnel geoip asset has no rules")
    val result = linkedSetOf<String>()
    repeat(rules.length()) { ruleIndex ->
      val cidrs = rules.optJSONObject(ruleIndex)?.optJSONArray("ip_cidr") ?: return@repeat
      repeat(cidrs.length()) { cidrIndex ->
        val cidr = cidrs.optString(cidrIndex).trim()
        require(isValidCidr(cidr)) { "Invalid TrustTunnel geoip CIDR: $cidr" }
        result += cidr
      }
    }
    require(result.isNotEmpty()) { "TrustTunnel geoip asset has no CIDRs" }
    return result.toList()
  }

  internal fun isValidCidr(value: String): Boolean {
    val separator = value.lastIndexOf('/')
    if (separator <= 0 || separator == value.lastIndex) return false
    val address = value.substring(0, separator)
    val prefixText = value.substring(separator + 1)
    if (!prefixText.matches(Regex("\\d+"))) return false
    val prefix = prefixText.toIntOrNull() ?: return false
    val addressBits = when {
      isIpv4(address) -> 32
      isIpv6(address) -> 128
      else -> return false
    }
    return prefix in 0..addressBits
  }

  private fun sha256Token(cidrs: List<String>): String {
    val payload = (cidrs.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(payload)
      .joinToString("") { byte -> "%02x".format(byte) }
  }

  private fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
      part.isNotEmpty() && part.length <= 3 && part.matches(Regex("\\d+")) &&
        part.toIntOrNull()?.let { it in 0..255 } == true
    }
  }

  private fun isIpv6(value: String): Boolean {
    if (value.isEmpty() || value.count { it == ':' } < 2) return false
    val hasCompression = value.contains("::")
    if (hasCompression && value.indexOf("::") != value.lastIndexOf("::")) return false
    val normalized = value.replace("::", ":")
    val groups = normalized.split(':').filter(String::isNotEmpty).toMutableList()
    if (groups.any { it.contains('.') }) {
      if (!isIpv4(groups.removeLastOrNull() ?: return false)) return false
      groups += listOf("0", "0")
    }
    if (groups.any { it.length !in 1..4 || !it.matches(Regex("[0-9A-Fa-f]+")) }) return false
    return if (hasCompression) groups.size < 8 else groups.size == 8
  }
}
