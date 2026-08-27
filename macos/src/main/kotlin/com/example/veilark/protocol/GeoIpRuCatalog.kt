package com.example.veilark.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Reads the build-time decompiled, hash-verified GeoIP asset without work at connect time. */
object GeoIpRuCatalog {
  private const val MAX_RULES = 50_000
  private const val MAX_JSON_BYTES = 8L * 1024L * 1024L
  internal const val EXPECTED_CIDR_COUNT = 10_859
  internal const val EXPECTED_TOKEN_SHA256 =
    "e8f53102eab91db9fb6329d79a1dd528564818dfe603018a9261138334bcbc1d"

  @Volatile private var cachedKey: String? = null
  @Volatile private var cachedCidrs: List<String>? = null

  @Synchronized
  fun load(json: File): List<String> {
    require(json.isFile && json.length() in 1..MAX_JSON_BYTES) { "geoip-ru.json is unavailable" }
    val key = "${json.canonicalPath}:${json.length()}:${json.lastModified()}"
    if (key == cachedKey) return requireNotNull(cachedCidrs)
    val parsed = parse(json.readText())
    require(parsed.size == EXPECTED_CIDR_COUNT) { "GeoIP RU network count is invalid" }
    require(tokenSha256(parsed) == EXPECTED_TOKEN_SHA256) { "GeoIP RU content hash is invalid" }
    cachedKey = key
    cachedCidrs = parsed
    return parsed
  }

  internal fun parse(json: String): List<String> {
    val root = JSONObject(json)
    val result = linkedSetOf<String>()
    collectRules(root.optJSONArray("rules") ?: error("GeoIP rule-set has no rules"), result)
    require(result.isNotEmpty() && result.size <= MAX_RULES) { "GeoIP rule-set size is invalid" }
    require(result.any(::isIpv4Cidr) && result.any(::isIpv6Cidr)) {
      "GeoIP rule-set must contain IPv4 and IPv6 networks"
    }
    return result.toList()
  }

  private fun collectRules(rules: JSONArray, result: MutableSet<String>) {
    for (index in 0 until rules.length()) {
      val rule = rules.optJSONObject(index) ?: continue
      val cidrs = rule.optJSONArray("ip_cidr")
      if (cidrs != null) {
        for (cidrIndex in 0 until cidrs.length()) {
          val cidr = cidrs.optString(cidrIndex).trim()
          require(isIpv4Cidr(cidr) || isIpv6Cidr(cidr)) { "Invalid GeoIP CIDR" }
          result += cidr
          require(result.size <= MAX_RULES) { "GeoIP rule-set is too large" }
        }
      }
      rule.optJSONArray("rules")?.let { collectRules(it, result) }
    }
  }

  internal fun isIpv4Cidr(value: String): Boolean {
    val parts = value.split('/', limit = 2)
    if (parts.size != 2 || parts[1].toIntOrNull() !in 0..32) return false
    val octets = parts[0].split('.')
    return octets.size == 4 && octets.all { it.isNotEmpty() && it.toIntOrNull() in 0..255 }
  }

  internal fun isIpv6Cidr(value: String): Boolean {
    val parts = value.split('/', limit = 2)
    if (parts.size != 2 || parts[1].toIntOrNull() !in 0..128 || ':' !in parts[0]) return false
    return runCatching { java.net.InetAddress.getByName(parts[0]).address.size == 16 }.getOrDefault(false)
  }

  internal fun tokenSha256(cidrs: List<String>): String {
    val payload = (cidrs.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256")
      .digest(payload)
      .joinToString("") { byte -> "%02x".format(byte) }
  }
}
