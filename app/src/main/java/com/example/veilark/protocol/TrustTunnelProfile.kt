package com.example.veilark.protocol

import com.adguard.trusttunnel.DeepLink
import com.adguard.trusttunnel.VpnServiceConfig

data class CompiledTrustTunnelProfile(
  val displayName: String,
  val config: String,
)

object TrustTunnelProfile {
  fun compile(deepLink: String): CompiledTrustTunnelProfile {
    require(deepLink.trim().startsWith("tt://")) {
      "Ссылка TrustTunnel должна начинаться с tt://"
    }
    var endpoint = DeepLink.decode(deepLink.trim()).trim()
    require(endpoint.startsWith("[endpoint]")) {
      "Ядро TrustTunnel вернуло некорректный профиль"
    }
    endpoint = optimizeEndpoint(endpoint)

    val name = Regex("""(?m)^name\s*=\s*"([^"]+)"""")
      .find(endpoint)
      ?.groupValues
      ?.get(1)
      ?.takeIf(String::isNotBlank)
      ?: "TrustTunnel"
    val config = """
      loglevel = "warn"
      vpn_mode = "general"
      killswitch_enabled = true
      post_quantum_group_enabled = true
      exclusions = []

      $endpoint

      [listener]

      [listener.tun]
      included_routes = ["0.0.0.0/0", "2000::/3"]
      excluded_routes = ["0.0.0.0/8", "10.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/3"]
      mtu_size = 1280
    """.trimIndent()
    require(VpnServiceConfig.parseToml(config) != null) {
      "Не удалось проверить конфигурацию TrustTunnel"
    }
    return CompiledTrustTunnelProfile(name, config)
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
}
