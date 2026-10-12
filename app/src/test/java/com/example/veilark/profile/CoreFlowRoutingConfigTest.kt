package com.example.veilark.profile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreFlowRoutingConfigTest {
  private val capabilities = CoreFlowCapabilities(1, true, true, true, 64, 1252)
  private fun session() = CoreLoopbackSession("127.0.0.1", 49152, "test-session-user", "test-session-password")
  private fun rejected(block: () -> Unit) {
    try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
  }

  @Test fun isolatedConfigHasOnlyAuthenticatedCoreAndDirectWithTunneledIpv4Dns() {
    var validationCalls = 0
    val config = CoreFlowRoutingConfig.build(session(), capabilities, ProfileSelection.ROUTING_ALL,
      vpnPackage = "test.veilark", validateConfig = { validationCalls++ })
    config.useJson { text ->
      val root = JSONObject(text)
      val outbounds = root.getJSONArray("outbounds")
      assertEquals(2, outbounds.length())
      val socks = outbounds.getJSONObject(0)
      assertEquals("socks", socks.getString("type"))
      assertEquals("127.0.0.1", socks.getString("server"))
      assertEquals("test-session-user", socks.getString("username"))
      assertFalse(socks.has("network")) // Both TCP and UDP, not TCP-only.
      assertFalse(socks.has("udp_over_tcp"))
      assertEquals("secure-dns", socks.getJSONObject("domain_resolver").getString("server"))
      val dns = root.getJSONObject("dns")
      assertEquals("ipv4_only", dns.getString("strategy"))
      assertEquals("auto", dns.getJSONArray("servers").getJSONObject(1).getString("detour"))
      val route = root.getJSONObject("route")
      assertEquals("secure-dns", route.getString("default_domain_resolver"))
      val rules = route.getJSONArray("rules")
      assertEquals(6, rules.getJSONObject(0).getInt("ip_version"))
      assertEquals("reject", rules.getJSONObject(0).getString("action"))
      assertEquals("resolve", rules.getJSONObject(3).getString("action"))
      assertFalse(rules.getJSONObject(3).has("server")) // Existing split DNS remains authoritative.
      assertTrue(rules.getJSONObject(rules.length()-1).getBoolean("udp_disable_domain_unmapping"))
      assertEquals("test.veilark", root.getJSONArray("inbounds").getJSONObject(0).getJSONArray("exclude_package").getString(0))
    }
    assertEquals(1, validationCalls)
    assertFalse(config.toString().contains("test-session-password"))
    assertFalse(session().toString().contains("test-session-user"))
  }

  @Test fun manualVpnOverridesDirectAndAssetsAreLocal() {
    val config = CoreFlowRoutingConfig.build(session(), capabilities, ProfileSelection.ROUTING_MANUAL,
      "geosite:telegram example.org", "geosite:google login.example.org",
      categoryRuleSets = mapOf("geosite:telegram" to "/data/telegram.json", "geosite:google" to "/data/google.json"),
      validateConfig = {})
    config.useJson {
      val root = JSONObject(it)
      val route = root.getJSONObject("route")
      val rules = route.getJSONArray("rules")
      assertEquals("auto", rules.getJSONObject(4).getString("outbound"))
      assertEquals("auto", rules.getJSONObject(5).getString("outbound"))
      assertEquals("direct", rules.getJSONObject(6).getString("outbound"))
      assertEquals("source", route.getJSONArray("rule_set").getJSONObject(0).getString("format"))
      assertEquals("bootstrap-dns", root.getJSONObject("dns").getJSONArray("rules").getJSONObject(0).getString("server"))
    }
    rejected { CoreFlowRoutingConfig.build(session(), capabilities, ProfileSelection.ROUTING_MANUAL,
      "geosite:telegram", validateConfig = {}) }
  }

  @Test fun russiaPresetRulesAndDnsAreReusedWithoutChangingPathsOrDirectPolicy() {
    val geo = ProfileSelection.GeoRuleSets("/data/existing/geoip-ru.srs", "/data/existing/geosite-ru.srs")
    val direct = SubscriptionParser().createRoutingTemplate(org.json.JSONArray()
      .put(JSONObject().put("type", "direct").put("tag", "auto")))
    val expected = JSONObject(ProfileSelection.applyRouting(direct.toString(), ProfileSelection.ROUTING_RU_DIRECT, geoRuleSets = geo))
    CoreFlowRoutingConfig.build(session(), capabilities, ProfileSelection.ROUTING_RU_DIRECT,
      geoRuleSets = geo, validateConfig = {}).useJson {
      val actual = JSONObject(it)
      assertEquals(expected.getJSONObject("route").getJSONArray("rule_set").toString(), actual.getJSONObject("route").getJSONArray("rule_set").toString())
      assertEquals(expected.getJSONObject("route").getJSONArray("rules").getJSONObject(1).toString(), actual.getJSONObject("route").getJSONArray("rules").getJSONObject(4).toString())
      assertEquals(expected.getJSONObject("dns").getJSONArray("rules").toString(), actual.getJSONObject("dns").getJSONArray("rules").toString())
    }
  }

  @Test fun allowlistedApplicationsKeepCoreOwnerOutsideTun() {
    CoreFlowRoutingConfig.build(session(), capabilities, ProfileSelection.ROUTING_ALL,
      applicationMode = ProfileSelection.APPS_ONLY,
      applications = setOf("test.telegram", "test.veilark"), vpnPackage = "test.veilark",
      validateConfig = {}).useJson {
      val tun = JSONObject(it).getJSONArray("inbounds").getJSONObject(0)
      assertEquals("[\"test.telegram\"]", tun.getJSONArray("include_package").toString())
      assertFalse(tun.has("exclude_package"))
    }
  }

  @Test fun invalidCapabilitiesAuthHostsDpiAndSchemaFailureCannotProduceConfig() {
    for (host in listOf("localhost", "::1", "0.0.0.0", "127.0.0.2", "example.org")) {
      rejected { CoreLoopbackSession(host, 49152, "u", "p") }
    }
    for (port in listOf(0, 65536, -1)) rejected { CoreLoopbackSession("127.0.0.1", port, "u", "p") }
    for (auth in listOf("", "x".repeat(256), "abc\n", "пароль")) {
      rejected { CoreLoopbackSession("127.0.0.1", 49152, auth, "valid") }
    }
    rejected { CoreFlowRoutingConfig.build(session(), capabilities.copy(ipv4UdpAssociate = false), ProfileSelection.ROUTING_ALL, validateConfig = {}) }
    rejected { CoreFlowRoutingConfig.build(session(), capabilities.copy(maximumUdpPayload = 1253), ProfileSelection.ROUTING_ALL, validateConfig = {}) }
    rejected { CoreFlowRoutingConfig.build(session(), capabilities, ProfileSelection.ROUTING_ALL,
      dpiMode = ProfileSelection.DPI_TLS_FRAGMENT, validateConfig = {}) }
    rejected { CoreFlowRoutingConfig.build(session(), capabilities, ProfileSelection.ROUTING_ALL,
      validateConfig = { throw IllegalArgumentException("Native schema rejected") }) }
  }
}
