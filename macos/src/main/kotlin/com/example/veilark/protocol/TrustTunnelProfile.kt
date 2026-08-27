package com.example.veilark.protocol

data class CompiledTrustTunnelProfile(
  val displayName: String,
  val config: String,
)

object TrustTunnelProfile {
  private val IPV6_LOCAL_ROUTES = listOf("::/128", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8")

  fun compile(deepLink: String): CompiledTrustTunnelProfile {
    require(deepLink.trim().startsWith("tt://", ignoreCase = true)) {
      "Ссылка TrustTunnel должна начинаться с tt://"
    }
    var endpoint = TrustTunnelDeepLink.toEndpointToml(deepLink.trim()).trim()
    require(endpoint.startsWith("[endpoint]")) {
      "Ядро TrustTunnel вернуло некорректный профиль"
    }
    endpoint = optimizeEndpoint(endpoint)

    val name = stringField(endpoint, "name")
      ?: stringField(endpoint, "hostname")
      ?: "TrustTunnel"
    val config = """
      loglevel = "warn"
      vpn_mode = "general"
      killswitch_enabled = true
      killswitch_allow_ports = []
      post_quantum_group_enabled = true
      exclusions = []

      $endpoint

      [listener]

      [listener.tun]
      bound_if = ""
      included_routes = ["0.0.0.0/0"]
      excluded_routes = ["0.0.0.0/8", "10.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/3"]
      mtu_size = 1280
      change_system_dns = true
    """.trimIndent()
    require(config.contains("[listener.tun]")) {
      "Не удалось проверить конфигурацию TrustTunnel"
    }
    return CompiledTrustTunnelProfile(name, prepareMacConfig(config))
  }

  /**
   * Saved profiles may still advertise IPv6 default routes while has_ipv6 is false.
   * On macOS that fails mactun route setup while the process stays alive.
   */
  fun prepareMacConfig(config: String): String {
    var text = config
    if (Regex("""(?m)^\s*has_ipv6\s*=\s*false\s*$""").containsMatchIn(text)) {
      text = text.replace(
        Regex("""included_routes\s*=\s*\[[^\]]*\]"""),
        """included_routes = ["0.0.0.0/0"]""",
      )
    }
    return text.replace(Regex("""(?m)^\s*client_random\s*=\s*""\s*\n?"""), "")
  }

  /**
   * Applies IP-only RU bypass using TrustTunnel's native general-mode exclusions.
   * The listener remains a full tunnel; no persistent routes are installed by Veilark itself.
   */
  fun applyGeoIpRuDirect(config: String, ruCidrs: List<String>): String {
    require(ruCidrs.isNotEmpty()) { "GeoIP RU is empty" }
    require(ruCidrs.all { GeoIpRuCatalog.isIpv4Cidr(it) || GeoIpRuCatalog.isIpv6Cidr(it) }) {
      "GeoIP RU contains an invalid network"
    }
    require(ruCidrs.any(GeoIpRuCatalog::isIpv4Cidr) && ruCidrs.any(GeoIpRuCatalog::isIpv6Cidr)) {
      "GeoIP RU must contain IPv4 and IPv6 networks"
    }

    var text = prepareMacConfig(config)
    text = replaceScalar(text, "vpn_mode", "\"general\"")
    text = replaceArray(text, "exclusions", readArray(text, "exclusions") + ruCidrs)

    val listenerExclusions = linkedSetOf<String>().apply {
      addAll(readArray(text, "excluded_routes"))
      addAll(IPV6_LOCAL_ROUTES)
      addAll(endpointLiteralRoutes(text))
    }
    text = replaceArray(text, "excluded_routes", listenerExclusions.toList())
    require(text.toByteArray(Charsets.UTF_8).size < 4 * 1024 * 1024) {
      "TrustTunnel GeoIP configuration is too large"
    }
    return text
  }

  internal fun optimizeEndpoint(endpoint: String): String =
    forceSetting(
      forceSetting(
        forceSetting(endpoint, "anti_dpi", "true"),
        "upstream_protocol",
        "\"http2\"",
      ),
      "has_ipv6",
      "false",
    )

  private fun forceSetting(endpoint: String, name: String, value: String): String {
    val setting = "$name = $value"
    val pattern = Regex("""(?m)^\s*${Regex.escape(name)}\s*=.*$""")
    return if (pattern.containsMatchIn(endpoint)) {
      endpoint.replace(pattern, setting)
    } else {
      endpoint.replaceFirst("[endpoint]", "[endpoint]\n$setting")
    }
  }

  private fun replaceScalar(config: String, name: String, value: String): String {
    val pattern = Regex("""(?m)^\s*${Regex.escape(name)}\s*=.*$""")
    require(pattern.containsMatchIn(config)) { "Missing TrustTunnel setting: $name" }
    return config.replaceFirst(pattern, "$name = $value")
  }

  private fun replaceArray(config: String, name: String, values: Collection<String>): String {
    val pattern = Regex("""(?m)^\s*${Regex.escape(name)}\s*=\s*\[[^\]]*\]\s*$""")
    require(pattern.containsMatchIn(config)) { "Missing TrustTunnel setting: $name" }
    val rendered = values.distinct().joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    return config.replaceFirst(pattern, "$name = $rendered")
  }

  private fun readArray(config: String, name: String): List<String> {
    val body = Regex("""(?m)^\s*${Regex.escape(name)}\s*=\s*\[([^\]]*)\]\s*$""")
      .find(config)?.groupValues?.get(1) ?: return emptyList()
    return Regex(""""([^"\\]*)"""").findAll(body).map { it.groupValues[1] }.toList()
  }

  private fun endpointLiteralRoutes(config: String): List<String> {
    val values = readArray(config, "addresses") + listOfNotNull(stringField(config, "hostname"))
    return values.mapNotNull { address ->
      val host = when {
        address.startsWith("[") -> address.substringAfter('[').substringBefore(']')
        address.count { it == ':' } == 1 -> address.substringBeforeLast(':')
        else -> address
      }
      when {
        GeoIpRuCatalog.isIpv4Cidr("$host/32") -> "$host/32"
        GeoIpRuCatalog.isIpv6Cidr("$host/128") -> "$host/128"
        else -> null
      }
    }
  }

  private fun stringField(toml: String, name: String): String? =
    Regex("""(?m)^${Regex.escape(name)}\s*=\s*"([^"]+)"""")
      .find(toml)
      ?.groupValues
      ?.get(1)
      ?.takeIf(String::isNotBlank)
}
