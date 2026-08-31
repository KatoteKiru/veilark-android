package com.example.veilark.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads the build-time decompiled RU domain rule-set. TrustTunnel's general
 * mode accepts domain exclusions, so keeping these domains alongside GeoIP
 * networks is required for split DNS as well as split routing.
 */
object GeoSiteRuCatalog {
  private const val MAX_RULES = 100_000
  private const val MAX_JSON_BYTES = 16L * 1024L * 1024L

  @Volatile private var cachedKey: String? = null
  @Volatile private var cachedDomains: List<String>? = null

  @Synchronized
  fun load(json: File): List<String> {
    require(json.isFile && json.length() in 1..MAX_JSON_BYTES) {
      "geosite-category-ru.json is unavailable"
    }
    val key = "${json.canonicalPath}:${json.length()}:${json.lastModified()}"
    if (key == cachedKey) return requireNotNull(cachedDomains)
    val parsed = parse(json.readText())
    cachedKey = key
    cachedDomains = parsed
    return parsed
  }

  internal fun parse(json: String): List<String> {
    val root = JSONObject(json)
    val result = linkedSetOf<String>()
    collectRules(root.optJSONArray("rules") ?: error("Geosite rule-set has no rules"), result)
    require(result.isNotEmpty() && result.size <= MAX_RULES) {
      "Geosite rule-set size is invalid"
    }
    return result.toList()
  }

  private fun collectRules(rules: JSONArray, result: MutableSet<String>) {
    for (index in 0 until rules.length()) {
      val rule = rules.optJSONObject(index) ?: continue
      collectValues(rule.optJSONArray("domain"), result)
      collectValues(rule.optJSONArray("domain_suffix"), result)
      // Keyword/regex entries cannot be represented safely as TrustTunnel
      // exclusions. They remain covered by the IP rule-set and are skipped.
      rule.optJSONArray("rules")?.let { collectRules(it, result) }
    }
  }

  private fun collectValues(values: JSONArray?, result: MutableSet<String>) {
    if (values == null) return
    for (index in 0 until values.length()) {
      val domain = values.optString(index)
        .trim()
        .lowercase()
        .removeSuffix(".")
        .removePrefix(".")
      require(isDomain(domain)) { "Invalid geosite domain" }
      result += domain
      require(result.size <= MAX_RULES) { "Geosite rule-set is too large" }
    }
  }

  private fun isDomain(value: String): Boolean {
    if (value.isBlank() || value.length > 253 || value.startsWith("*")) return false
    return value.split('.').all { label ->
      label.isNotEmpty() && label.length <= 63 &&
        label.first() != '-' && label.last() != '-' &&
        label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
  }
}
