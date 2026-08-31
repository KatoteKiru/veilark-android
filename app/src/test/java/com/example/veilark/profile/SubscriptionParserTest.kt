package com.example.veilark.profile

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class SubscriptionParserTest {
  private val parser = SubscriptionParser()

  @Test
  fun compilesRealityTrojanAndWidePortHysteria() {
    val links = listOf(
      "vless://11111111-1111-1111-1111-111111111111@203.0.113.1:443" +
        "?security=reality&type=tcp&sni=example.com&fp=chrome&pbk=public&sid=abcd" +
        "&flow=xtls-rprx-vision#Reality",
      "trojan://secret@203.0.113.2:443?security=tls&type=ws&sni=example.com" +
        "&host=example.com&path=%2Ftrojan#Trojan",
      "hysteria2://password@203.0.113.3:20000-50000?sni=example.com" +
        "&obfs=salamander&obfs-password=mask#Hysteria",
      "tt://opaque-trust-profile",
    ).joinToString("\n")

    val result = parser.compile(links.toByteArray())
    val json = JSONObject(result.json)
    val outbounds = json.getJSONArray("outbounds")

    assertEquals(3, result.profileCount)
    assertEquals(listOf("tt://opaque-trust-profile"), result.trustTunnelLinks)
    assertEquals(listOf("VLESS", "Trojan", "Hysteria 2"), result.nodes.map { it.protocol })
    assertEquals("urltest", outbounds.getJSONObject(0).getString("type"))
    assertEquals(
      "https://cp.cloudflare.com/generate_204",
      outbounds.getJSONObject(0).getString("url"),
    )
    assertEquals(1280, json.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
    val dns = json.getJSONObject("dns")
    assertTrue(dns.getBoolean("reverse_mapping"))
    assertEquals(
      "cloudflare-dns.com",
      dns.getJSONArray("servers").getJSONObject(1)
        .getJSONObject("tls").getString("server_name"),
    )
    assertEquals("vless", outbounds.getJSONObject(1).getString("type"))
    assertFalse(
      outbounds.getJSONObject(1).getJSONObject("tls").getBoolean("insecure"),
    )
    assertEquals("trojan", outbounds.getJSONObject(2).getString("type"))
    assertEquals(
      "20000:50000",
      outbounds.getJSONObject(3).getJSONArray("server_ports").getString(0),
    )
  }

  @Test
  fun selectsAnImportedNodeWithoutRemovingOtherOutbounds() {
    val links = listOf(
      "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL",
      "trojan://secret@203.0.113.3:443?security=tls&sni=example.com#DE",
    ).joinToString("\n")
    val result = parser.compile(links.toByteArray())
    val selected = JSONObject(
      ProfileSelection.select(result.json, result.nodes.last().tag, result.nodes),
    )

    assertEquals(result.nodes.last().tag, selected.getJSONObject("route").getString("final"))
    assertEquals(4, selected.getJSONArray("outbounds").length())
    assertEquals(
      result.nodes.last().tag,
      selected.getJSONObject("dns").getJSONArray("servers")
        .getJSONObject(1).getString("detour"),
    )
  }

  @Test
  fun appliesManualDomainNetworkAndPerApplicationRouting() {
    val result = SubscriptionParser().compile(
      "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
        .toByteArray(),
    )
    var configured = ProfileSelection.applyRouting(
      result.json,
      ProfileSelection.ROUTING_MANUAL,
      directEntries = "gosuslugi.ru\n10.0.0.0/8",
      vpnEntries = "youtube.com googlevideo.com",
    )
    configured = ProfileSelection.applyApplications(
      configured,
      ProfileSelection.APPS_ONLY,
      setOf("org.telegram.messenger"),
      "app.veilark.test",
    )
    configured = ProfileSelection.applyDpiProtection(
      configured,
      ProfileSelection.DPI_TLS_FRAGMENT,
    )
    val root = JSONObject(configured)
    val route = root.getJSONObject("route")
    assertFalse(route.has("rule_set"))
    assertEquals(4, route.getJSONArray("rules").length())
    assertEquals(
      "sniff",
      route.getJSONArray("rules").getJSONObject(0).getString("action"),
    )
    assertEquals(
      "youtube.com",
      route.getJSONArray("rules").getJSONObject(1)
        .getJSONArray("domain_suffix").getString(0),
    )
    assertEquals(
      "auto",
      route.getJSONArray("rules").getJSONObject(1).getString("outbound"),
    )
    assertEquals(
      "direct",
      route.getJSONArray("rules").getJSONObject(2).getString("outbound"),
    )
    assertEquals(
      "10.0.0.0/8",
      route.getJSONArray("rules").getJSONObject(3)
        .getJSONArray("ip_cidr").getString(0),
    )
    val packages = root.getJSONArray("inbounds").getJSONObject(0)
      .getJSONArray("include_package")
    assertEquals(1, packages.length())
    assertEquals("org.telegram.messenger", packages.getString(0))
    val tls = root.getJSONArray("outbounds").getJSONObject(1).getJSONObject("tls")
    assertTrue(tls.getBoolean("fragment"))
    assertEquals("20ms", tls.getString("fragment_fallback_delay"))
    val selected = JSONObject(
      ProfileSelection.select(configured, result.nodes.first().tag, result.nodes),
    )
    assertFalse(
      selected.getJSONObject("route").getJSONArray("rules")
        .getJSONObject(0).has("outbound"),
    )
    assertEquals(
      result.nodes.first().tag,
      selected.getJSONObject("route").getJSONArray("rules")
        .getJSONObject(1).getString("outbound"),
    )
    System.getenv("SING_BOX_CHECKER")?.takeIf { File(it).isFile }?.let { checker ->
      val config = Files.createTempFile("veilark-routing-", ".json").toFile()
      try {
        config.writeText(configured)
        val process = ProcessBuilder(checker, "check", "-c", config.path)
          .redirectErrorStream(true)
          .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("sing-box rejected routing config: $output", 0, process.waitFor())
      } finally {
        config.delete()
      }
    }
  }

  @Test
  fun bypassModeExcludesSelectedApplicationAndVeilark() {
    val result = parser.compile(
      "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
        .toByteArray(),
    )
    val configured = JSONObject(
      ProfileSelection.applyApplications(
        result.json,
        ProfileSelection.APPS_BYPASS,
        setOf("com.example.direct", "app.veilark.test"),
        "app.veilark.test",
      ),
    )
    val tun = configured.getJSONArray("inbounds").getJSONObject(0)
    assertFalse(tun.has("include_package"))
    val excluded = tun.getJSONArray("exclude_package")
    assertEquals(2, excluded.length())
    assertEquals("app.veilark.test", excluded.getString(0))
    assertEquals("com.example.direct", excluded.getString(1))
  }

  @Test
  fun allTrafficModeExcludesTheVpnProcessFromItsOwnTun() {
    val result = parser.compile(
      "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
        .toByteArray(),
    )

    val configured = JSONObject(
      ProfileSelection.applyApplications(
        result.json,
        ProfileSelection.APPS_ALL,
        emptySet(),
        "app.veilark.test",
      ),
    )

    val tun = configured.getJSONArray("inbounds").getJSONObject(0)
    assertFalse(tun.has("include_package"))
    assertEquals(
      "app.veilark.test",
      tun.getJSONArray("exclude_package").getString(0),
    )
  }

  @Test
  fun allTrafficModeRemovesManualRules() {
    val result = parser.compile(
      "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
        .toByteArray(),
    )
    val manual = ProfileSelection.applyRouting(
      result.json,
      ProfileSelection.ROUTING_MANUAL,
      directEntries = "example.ru",
      vpnEntries = "youtube.com",
    )
    val allTraffic = JSONObject(
      ProfileSelection.applyRouting(manual, ProfileSelection.ROUTING_ALL),
    )
    assertFalse(allTraffic.getJSONObject("route").has("rules"))
    assertEquals("auto", allTraffic.getJSONObject("route").getString("final"))
  }

  @Test
  fun russianDirectModeUsesPackagedLocalRuleSets() {
    val result = parser.compile(
      "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
        .toByteArray(),
    )
    val configured = JSONObject(
      ProfileSelection.applyRouting(
        result.json,
        ProfileSelection.ROUTING_RU_DIRECT,
        geoRuleSets = ProfileSelection.GeoRuleSets(
          geoIpRuPath = "/data/user/0/app.veilark.test/no_backup/geo/geoip-ru.srs",
          geoSiteRuPath =
            "/data/user/0/app.veilark.test/no_backup/geo/geosite-category-ru.srs",
        ),
      ),
    )
    val route = configured.getJSONObject("route")
    assertEquals(2, route.getJSONArray("rule_set").length())
    assertEquals(
      "/data/user/0/app.veilark.test/no_backup/geo/geoip-ru.srs",
      route.getJSONArray("rule_set").getJSONObject(0).getString("path"),
    )
    val rules = route.getJSONArray("rules")
    assertEquals("sniff", rules.getJSONObject(0).getString("action"))
    assertEquals("direct", rules.getJSONObject(1).getString("outbound"))
    assertEquals("geoip-ru", rules.getJSONObject(1).getJSONArray("rule_set").getString(1))

    val allTraffic = JSONObject(
      ProfileSelection.applyRouting(configured.toString(), ProfileSelection.ROUTING_ALL),
    )
    assertFalse(allTraffic.getJSONObject("route").has("rule_set"))
    assertFalse(allTraffic.getJSONObject("route").has("rules"))
  }

  @Test
  fun russianDirectModePassesNativeSingBoxCheckWhenAssetsAreAvailable() {
    val checker = System.getenv("SING_BOX_CHECKER")
    val ruleDirectory = System.getenv("VEILARK_GEO_RULE_DIRECTORY")
    assumeTrue(!checker.isNullOrBlank() && File(checker).isFile)
    assumeTrue(!ruleDirectory.isNullOrBlank() && File(ruleDirectory).isDirectory)

    val result = parser.compile(
      "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
        .toByteArray(),
    )
    val config = Files.createTempFile("veilark-geo-", ".json").toFile()
    try {
      config.writeText(
        ProfileSelection.applyRouting(
          result.json,
          ProfileSelection.ROUTING_RU_DIRECT,
          geoRuleSets = ProfileSelection.GeoRuleSets(
            geoIpRuPath = File(ruleDirectory, "geoip-ru.srs").absolutePath,
            geoSiteRuPath = File(ruleDirectory, "geosite-category-ru.srs").absolutePath,
          ),
        ),
      )
      val process = ProcessBuilder(checker, "check", "-c", config.path)
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      assertEquals("sing-box rejected geo routing config: $output", 0, process.waitFor())
    } finally {
      config.delete()
    }
  }

  @Test
  fun decodesBase64Subscription() {
    val link = "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
    val payload = Base64.getEncoder().encode(link.toByteArray())
    val result = parser.compile(payload)

    assertEquals(1, result.profileCount)
    assertTrue(result.json.contains("\"trojan\""))
  }

  @Test
  fun importsLinksFromJsonSubscriptionEnvelope() {
    val link = "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
    val encoded = Base64.getEncoder().withoutPadding()
      .encodeToString(link.toByteArray())
    val payload = JSONObject()
      .put("status", "ok")
      .put("data", JSONObject().put("subscription", encoded))
      .toString()
      .toByteArray()

    val result = parser.compile(payload)

    assertEquals(1, result.profileCount)
    assertEquals("Trojan", result.nodes.single().protocol)
  }

  @Test
  fun importsLinksFromJsonArray() {
    val payload = org.json.JSONArray()
      .put("vless://11111111-1111-1111-1111-111111111111@203.0.113.1:443" +
        "?security=tls&sni=example.com#External")
      .put(JSONObject().put("url", "tt://external-trust"))
      .toString()
      .toByteArray()

    val result = parser.compile(payload)

    assertEquals(1, result.profileCount)
    assertEquals(listOf("tt://external-trust"), result.trustTunnelLinks)
  }

  @Test
  fun importsMihomoYamlAndSkipsUnsupportedNodes() {
    val yaml = """
      mixed-port: 7890
      proxies:
        - name: NL VLESS
          type: vless
          server: nl.example.com
          port: 443
          uuid: 11111111-1111-1111-1111-111111111111
          tls: true
          servername: cdn.example.com
          client-fingerprint: chrome
          network: ws
          ws-opts:
            path: /edge
            headers:
              Host: cdn.example.com
        - name: DE Trojan
          type: trojan
          server: de.example.com
          port: 443
          password: secret
          sni: de.example.com
        - name: FI Hysteria
          type: hysteria2
          server: fi.example.com
          port: 20000-50000
          password: secret
          sni: fi.example.com
          obfs: salamander
          obfs-password: mask
        - name: Unsupported WireGuard
          type: wireguard
          server: wg.example.com
          port: 51820
      proxy-groups:
        - name: Auto
          type: url-test
          proxies: [NL VLESS, DE Trojan]
    """.trimIndent()

    val result = parser.compile(yaml.toByteArray())
    val root = JSONObject(result.json)

    assertEquals(3, result.profileCount)
    assertEquals(1, result.rejectedCount)
    assertEquals(listOf("VLESS", "Trojan", "Hysteria 2"), result.nodes.map { it.protocol })
    assertEquals(
      "/edge",
      root.getJSONArray("outbounds").getJSONObject(1)
        .getJSONObject("transport").getString("path"),
    )
    assertEquals(
      "20000:50000",
      root.getJSONArray("outbounds").getJSONObject(3)
        .getJSONArray("server_ports").getString(0),
    )
  }

  @Test
  fun importsInlineMihomoProviderPayload() {
    val yaml = """
      proxy-providers:
        remote:
          type: inline
          payload:
            - name: External SS
              type: ss
              server: 203.0.113.20
              port: 8388
              cipher: aes-256-gcm
              password: secret
    """.trimIndent()

    val result = parser.compile(yaml.toByteArray())

    assertEquals(1, result.profileCount)
    assertEquals("Shadowsocks", result.nodes.single().protocol)
  }

  @Test
  fun importsVmessShadowsocksTuicAndAnyTls() {
    val vmess = JSONObject()
      .put("v", "2")
      .put("ps", "VMess NL")
      .put("add", "203.0.113.10")
      .put("port", "443")
      .put("id", "11111111-1111-1111-1111-111111111111")
      .put("aid", "0")
      .put("scy", "auto")
      .put("net", "ws")
      .put("path", "/ws")
      .put("host", "cdn.example.com")
      .put("tls", "tls")
      .put("sni", "cdn.example.com")
      .toString()
    val vmessLink = "vmess://" + Base64.getEncoder()
      .withoutPadding()
      .encodeToString(vmess.toByteArray())
    val ssCredentials = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("aes-256-gcm:secret".toByteArray())
    val links = listOf(
      vmessLink,
      "ss://$ssCredentials@203.0.113.11:8388#SS",
      "tuic://11111111-1111-1111-1111-111111111111:password@203.0.113.12:443" +
        "?sni=tuic.example.com#TUIC",
      "anytls://password@203.0.113.13:443?sni=any.example.com#AnyTLS",
    ).joinToString("\n")

    val result = parser.compile(links.toByteArray())
    assertEquals(
      listOf("VMess", "Shadowsocks", "TUIC", "AnyTLS"),
      result.nodes.map(ConnectionNode::protocol),
    )
    assertEquals(4, result.profileCount)
  }

  @Test
  fun importsSupportedOutboundsFromSingBoxJson() {
    val source = JSONObject()
      .put(
        "outbounds",
        org.json.JSONArray()
          .put(JSONObject().put("type", "direct").put("tag", "direct"))
          .put(
            JSONObject()
              .put("type", "trojan")
              .put("tag", "External")
              .put("server", "203.0.113.20")
              .put("server_port", 443)
              .put("password", "secret")
              .put(
                "tls",
                JSONObject()
                  .put("enabled", true)
                  .put("server_name", "example.com"),
              ),
          ),
      )

    val result = parser.compile(source.toString().toByteArray())

    assertEquals(1, result.profileCount)
    assertEquals("Trojan", result.nodes.single().protocol)
    assertEquals(
      "urltest",
      JSONObject(result.json).getJSONArray("outbounds").getJSONObject(0).getString("type"),
    )
  }

  @Test
  fun importsRemnawaveXrayJson() {
    val source = JSONObject()
      .put(
        "outbounds",
        org.json.JSONArray().put(
          JSONObject()
            .put("tag", "External Reality")
            .put("protocol", "vless")
            .put(
              "settings",
              JSONObject().put(
                "vnext",
                org.json.JSONArray().put(
                  JSONObject()
                    .put("address", "203.0.113.40")
                    .put("port", 443)
                    .put(
                      "users",
                      org.json.JSONArray().put(
                        JSONObject()
                          .put("id", "11111111-1111-1111-1111-111111111111")
                          .put("flow", "xtls-rprx-vision"),
                      ),
                    ),
                ),
              ),
            )
            .put(
              "streamSettings",
              JSONObject()
                .put("network", "tcp")
                .put("security", "reality")
                .put(
                  "realitySettings",
                  JSONObject()
                    .put("serverName", "example.com")
                    .put(
                      "publicKey",
                      "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    )
                    .put("shortId", "abcd")
                    .put("fingerprint", "chrome"),
                ),
            ),
        ),
      )

    val result = parser.compile(source.toString().toByteArray())
    val outbound = JSONObject(result.json).getJSONArray("outbounds").getJSONObject(1)

    assertEquals(1, result.profileCount)
    assertEquals("vless", outbound.getString("type"))
    assertTrue(outbound.getJSONObject("tls").getJSONObject("reality").getBoolean("enabled"))
  }

  @Test
  fun importsBase64EncodedXrayJson() {
    val source = JSONObject()
      .put(
        "outbounds",
        org.json.JSONArray().put(
          JSONObject()
            .put("tag", "External Trojan")
            .put("protocol", "trojan")
            .put(
              "settings",
              JSONObject().put(
                "servers",
                org.json.JSONArray().put(
                  JSONObject()
                    .put("address", "203.0.113.41")
                    .put("port", 443)
                    .put("password", "secret"),
                ),
              ),
            )
            .put(
              "streamSettings",
              JSONObject()
                .put("security", "tls")
                .put(
                  "tlsSettings",
                  JSONObject().put("serverName", "example.com"),
                ),
            ),
        ),
      )
    val payload = Base64.getEncoder().encode(source.toString().toByteArray())

    val result = parser.compile(payload)

    assertEquals(1, result.profileCount)
    assertEquals("Trojan", result.nodes.single().protocol)
  }

  @Test
  fun extractsTrustOnlySubscriptionForIndependentRefresh() {
    val payload = "tt://first\ntt://second\ntt://first\n".toByteArray()

    assertEquals(
      listOf("tt://first", "tt://second"),
      parser.extractTrustTunnelLinks(payload),
    )
  }

  @Test
  fun generatedConfigPassesNativeSingBoxCheckWhenCheckerIsAvailable() {
    val checker = System.getenv("SING_BOX_CHECKER")
    assumeTrue(!checker.isNullOrBlank() && File(checker).isFile)
    val links = listOf(
      "vless://11111111-1111-1111-1111-111111111111@203.0.113.1:443" +
        "?security=reality&type=tcp&sni=example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abcd" +
        "&flow=xtls-rprx-vision#Reality",
      "trojan://secret@203.0.113.2:443?security=tls&type=ws&sni=example.com" +
        "&host=example.com&path=%2Ftrojan#Trojan",
      "hysteria2://password@203.0.113.3:20000-50000?sni=example.com" +
        "&obfs=salamander&obfs-password=mask#Hysteria",
    ).joinToString("\n")
    val config = Files.createTempFile("veilark-", ".json").toFile()
    try {
      config.writeText(parser.compile(links.toByteArray()).json)
      val process = ProcessBuilder(checker, "check", "-c", config.path)
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      val exit = process.waitFor()
      assertEquals("sing-box rejected generated config: $output", 0, exit)
    } finally {
      config.delete()
    }
  }

  @Test
  fun liveSubscriptionCompilesWhenExplicitlyProvided() = runBlocking {
    val checker = System.getenv("SING_BOX_CHECKER")
    val subscription = System.getenv("VEILARK_TEST_SUBSCRIPTION")
    assumeTrue(!subscription.isNullOrBlank())

    val result = parser.compile(SubscriptionFetcher.fetch(requireNotNull(subscription)))
    assertTrue("Live subscription did not contain supported profiles", result.profileCount > 0)
    System.getenv("VEILARK_EXPECT_SING_BOX_PROFILES")?.toIntOrNull()?.let { expected ->
      assertEquals(expected, result.profileCount)
    }
    System.getenv("VEILARK_CONFIG_OUTPUT")?.takeIf(String::isNotBlank)?.let {
      File(it).writeText(result.json)
    }
    System.getenv("VEILARK_PROXY_CONFIG_OUTPUT")?.takeIf(String::isNotBlank)?.let { path ->
      val proxyConfig = JSONObject(result.json)
      proxyConfig.put(
        "inbounds",
        org.json.JSONArray().put(
          JSONObject()
            .put("type", "mixed")
            .put("tag", "diagnostic-in")
            .put("listen", "127.0.0.1")
            .put("listen_port", 20808),
        ),
      )
      File(path).writeText(proxyConfig.toString(2))
    }
    System.getenv("VEILARK_SELECTED_CONFIG_DIRECTORY")
      ?.takeIf(String::isNotBlank)
      ?.let(::File)
      ?.also(File::mkdirs)
      ?.let { directory ->
        result.nodes.forEachIndexed { index, node ->
          val selected = JSONObject(
            ProfileSelection.select(result.json, node.tag, result.nodes),
          )
          selected.put(
            "inbounds",
            org.json.JSONArray().put(
              JSONObject()
                .put("type", "mixed")
                .put("tag", "diagnostic-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", 20808),
            ),
          )
          File(directory, "node-${index + 1}.json").writeText(selected.toString(2))
        }
      }
    System.getenv("VEILARK_EXPECT_TRUST_PROFILES")?.toIntOrNull()?.let { expected ->
      assertEquals(expected, result.trustTunnelLinks.size)
    }
    if (checker.isNullOrBlank() || !File(checker).isFile) return@runBlocking
    val config = Files.createTempFile("veilark-live-", ".json").toFile()
    try {
      config.writeText(result.json)
      val process = ProcessBuilder(checker, "check", "-c", config.path)
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      assertEquals("sing-box rejected live subscription config: $output", 0, process.waitFor())
    } finally {
      config.delete()
    }
  }
}
