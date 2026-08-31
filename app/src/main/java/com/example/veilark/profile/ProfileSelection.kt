package com.example.veilark.profile

import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

object ProfileSelection {
  const val AUTOMATIC_TAG = "auto"
  const val ROUTING_ALL = "all"
  const val ROUTING_MANUAL = "manual"
  const val ROUTING_RU_DIRECT = "ru_direct"
  const val APPS_ALL = "all"
  const val APPS_ONLY = "only"
  const val APPS_BYPASS = "bypass"
  const val DPI_OFF = "off"
  const val DPI_TLS_FRAGMENT = "tls_fragment"

  data class GeoRuleSets(
    val geoIpRuPath: String,
    val geoSiteRuPath: String,
  )

  fun encodeNodes(nodes: List<ConnectionNode>): String =
    JSONArray().apply {
      nodes.forEach { node ->
        put(
          JSONObject()
            .put("tag", node.tag)
            .put("name", node.name)
            .put("protocol", node.protocol),
        )
      }
    }.toString()

  fun decodeNodes(value: String?): List<ConnectionNode> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching {
      val array = JSONArray(value)
      buildList {
        repeat(array.length()) { index ->
          val item = array.getJSONObject(index)
          add(
            ConnectionNode(
              tag = item.getString("tag"),
              name = item.getString("name"),
              protocol = item.getString("protocol"),
            ),
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  fun select(config: String, tag: String, nodes: List<ConnectionNode>): String {
    require(tag == AUTOMATIC_TAG || nodes.any { it.tag == tag }) {
      "Выбранный узел отсутствует в подписке"
    }
    val root = JSONObject(config)
    val route = root.getJSONObject("route")
    route.put("final", tag)
    val dnsServers = root.optJSONObject("dns")?.optJSONArray("servers")
    if (dnsServers != null) {
      repeat(dnsServers.length()) { index ->
        val server = dnsServers.optJSONObject(index) ?: return@repeat
        if (server.optString("tag") == "secure-dns") server.put("detour", tag)
      }
    }
    route.optJSONArray("rules")?.let { rules ->
      repeat(rules.length()) { index ->
        val rule = rules.optJSONObject(index) ?: return@repeat
        if (rule.has("outbound") && rule.optString("outbound") != "direct") {
          rule.put("outbound", tag)
        }
      }
    }
    return root.toString(2)
  }

  fun applyRouting(
    config: String,
    mode: String,
    directEntries: String = "",
    vpnEntries: String = "",
    geoRuleSets: GeoRuleSets? = null,
  ): String {
    require(mode in setOf(ROUTING_ALL, ROUTING_MANUAL, ROUTING_RU_DIRECT))
    val root = JSONObject(config)
    val route = root.getJSONObject("route")
    val finalOutbound = route.getString("final")
    route.remove("rules")
    route.remove("rule_set")
    when (mode) {
      ROUTING_ALL -> Unit
      ROUTING_MANUAL -> {
        val direct = parseRoutingEntries(directEntries)
        val vpn = parseRoutingEntries(vpnEntries)
        require(
          direct.domains.isNotEmpty() || direct.networks.isNotEmpty() ||
            vpn.domains.isNotEmpty() || vpn.networks.isNotEmpty(),
        ) {
          "Добавьте хотя бы один домен или IP-диапазон"
        }
        val rules = JSONArray().put(JSONObject().put("action", "sniff"))
        addRule(rules, vpn, finalOutbound)
        addRule(rules, direct, "direct")
        route.put("rules", rules)
      }
      ROUTING_RU_DIRECT -> {
        val local = requireNotNull(geoRuleSets) {
          "Файлы геомаршрутизации не установлены"
        }
        require(local.geoIpRuPath.isNotBlank() && local.geoSiteRuPath.isNotBlank()) {
          "Файлы геомаршрутизации не установлены"
        }
        route.put(
          "rule_set",
          JSONArray()
            .put(localRuleSet("geoip-ru", local.geoIpRuPath))
            .put(localRuleSet("geosite-category-ru", local.geoSiteRuPath)),
        )
        route.put(
          "rules",
          JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(
              JSONObject()
                .put(
                  "rule_set",
                  JSONArray().put("geosite-category-ru").put("geoip-ru"),
                )
                .put("outbound", "direct"),
            ),
        )
      }
    }
    return root.toString(2)
  }

  private fun localRuleSet(tag: String, path: String): JSONObject =
    JSONObject()
      .put("type", "local")
      .put("tag", tag)
      .put("format", "binary")
      .put("path", path)

  fun applyApplications(
    config: String,
    mode: String,
    packages: Set<String>,
    vpnPackage: String? = null,
  ): String {
    require(mode in setOf(APPS_ALL, APPS_ONLY, APPS_BYPASS))
    if (mode != APPS_ALL) require(packages.isNotEmpty()) { "Не выбраны приложения" }
    val root = JSONObject(config)
    val tun = root.getJSONArray("inbounds").getJSONObject(0)
    tun.remove("include_package")
    tun.remove("exclude_package")
    when (mode) {
      // The VPN process must never send its own protected transport through the
      // TUN it owns. In allow-list mode it is implicitly outside the tunnel;
      // Android does not permit mixing allowed and disallowed application rules.
      APPS_ALL -> tun.put("exclude_package", JSONArray(listOfNotNull(vpnPackage).sorted()))
      APPS_ONLY -> tun.put(
        "include_package",
        JSONArray((packages - listOfNotNull(vpnPackage).toSet()).sorted()),
      )
      APPS_BYPASS -> tun.put(
        "exclude_package",
        JSONArray((packages + listOfNotNull(vpnPackage)).sorted()),
      )
    }
    return root.toString(2)
  }

  fun applyDpiProtection(config: String, mode: String): String {
    require(mode in setOf(DPI_OFF, DPI_TLS_FRAGMENT))
    val root = JSONObject(config)
    val outbounds = root.getJSONArray("outbounds")
    repeat(outbounds.length()) { index ->
      val outbound = outbounds.optJSONObject(index) ?: return@repeat
      val tls = outbound.optJSONObject("tls") ?: return@repeat
      tls.remove("fragment")
      tls.remove("fragment_fallback_delay")
      tls.remove("record_fragment")
      if (mode == DPI_TLS_FRAGMENT && tls.optBoolean("enabled", true)) {
        tls.put("fragment", true)
        tls.put("fragment_fallback_delay", "20ms")
      }
    }
    return root.toString(2)
  }

  private fun addRule(rules: JSONArray, entries: RoutingEntries, outbound: String) {
    if (entries.domains.isNotEmpty()) {
      rules.put(
        JSONObject()
          .put("domain_suffix", JSONArray(entries.domains))
          .put("outbound", outbound),
      )
    }
    if (entries.networks.isNotEmpty()) {
      rules.put(
        JSONObject()
          .put("ip_cidr", JSONArray(entries.networks))
          .put("outbound", outbound),
      )
    }
  }

  private fun parseRoutingEntries(value: String): RoutingEntries {
    val domains = linkedSetOf<String>()
    val networks = linkedSetOf<String>()
    value.split(Regex("""[\s,;]+"""))
      .map(String::trim)
      .filter(String::isNotEmpty)
      .forEach { raw ->
        val candidate = raw.trim()
        if (candidate.contains('/') && !candidate.startsWith("http", ignoreCase = true)) {
          networks += normalizeNetwork(candidate)
        } else {
          val entry = candidate
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .removePrefix("*.")
            .removePrefix(".")
            .trimEnd('.')
            .lowercase()
          require(entry.isNotBlank()) { "Пустое правило маршрутизации" }
          if (entry.matches(IPV4)) {
            validateIpv4(entry, raw)
            networks += "$entry/32"
          } else if (entry.contains(':') && entry.matches(IPV6)) {
            networks += "$entry/128"
          } else {
            val ascii = runCatching { IDN.toASCII(entry) }
              .getOrElse { throw IllegalArgumentException("Некорректный домен: $raw") }
            require(ascii.matches(DOMAIN)) { "Некорректный домен: $raw" }
            domains += ascii
          }
        }
      }
    return RoutingEntries(domains.toList(), networks.toList())
  }

  private fun normalizeNetwork(value: String): String {
    val address = value.substringBefore('/')
    val prefix = value.substringAfter('/').toIntOrNull()
      ?: throw IllegalArgumentException("Некорректная сеть: $value")
    val ipv4 = address.matches(IPV4)
    if (ipv4) validateIpv4(address, value)
    val ipv6 = address.contains(':') && address.matches(IPV6)
    require((ipv4 && prefix in 0..32) || (ipv6 && prefix in 0..128)) {
      "Некорректная сеть: $value"
    }
    return "${address.lowercase()}/$prefix"
  }

  private fun validateIpv4(address: String, original: String) {
    require(address.split('.').all { it.toIntOrNull() in 0..255 }) {
      "Некорректный IPv4: $original"
    }
  }

  private data class RoutingEntries(
    val domains: List<String>,
    val networks: List<String>,
  )

  private val DOMAIN =
    Regex("""(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?""")
  private val IPV4 = Regex("""(?:\d{1,3}\.){3}\d{1,3}""")
  private val IPV6 = Regex("""[0-9a-fA-F:]+""")
}
