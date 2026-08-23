package com.example.veilark.protocol

data class CompiledTrustTunnelProfile(
  val displayName: String,
  val config: String,
)

object TrustTunnelProfile {
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

  private fun stringField(toml: String, name: String): String? =
    Regex("""(?m)^${Regex.escape(name)}\s*=\s*"([^"]+)"""")
      .find(toml)
      ?.groupValues
      ?.get(1)
      ?.takeIf(String::isNotBlank)
}
