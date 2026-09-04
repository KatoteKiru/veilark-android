package com.example.veilark.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-feature contract for the user-defined sing-box routing mode.
 *
 * Manual destination rules and Android application splitting are independent:
 * the former belongs to sing-box `route.rules`, while the latter belongs to
 * the TUN inbound. Keeping this composition covered prevents either settings
 * screen from silently erasing the other feature.
 */
class ManualRoutingContractTest {
  private fun baseConfig(): String = SubscriptionParser().compile(
    "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
      .toByteArray(),
  ).json

  @Test
  fun supportsCountryNeutralDomainAndNetworkRulesWithVpnPriority() {
    val configured = JSONObject(
      ProfileSelection.applyRouting(
        config = baseConfig(),
        mode = ProfileSelection.ROUTING_MANUAL,
        directEntries = "example.de 192.0.2.0/24",
        vpnEntries = "youtube.com 2001:db8::/32",
      ),
    )

    val rules = configured.getJSONObject("route").getJSONArray("rules")
    assertEquals("sniff", rules.getJSONObject(0).getString("action"))
    assertEquals("auto", rules.getJSONObject(1).getString("outbound"))
    assertEquals("youtube.com", rules.getJSONObject(1).getJSONArray("domain_suffix").getString(0))
    assertEquals("auto", rules.getJSONObject(2).getString("outbound"))
    assertEquals("2001:db8::/32", rules.getJSONObject(2).getJSONArray("ip_cidr").getString(0))
    assertEquals("direct", rules.getJSONObject(3).getString("outbound"))
    assertEquals("example.de", rules.getJSONObject(3).getJSONArray("domain_suffix").getString(0))
    assertEquals("direct", rules.getJSONObject(4).getString("outbound"))
    assertEquals("192.0.2.0/24", rules.getJSONObject(4).getJSONArray("ip_cidr").getString(0))
  }

  @Test
  fun applicationBypassAndManualDestinationsComposeWithoutOverwritingEachOther() {
    val withRoutes = ProfileSelection.applyRouting(
      config = baseConfig(),
      mode = ProfileSelection.ROUTING_MANUAL,
      directEntries = "bank.example",
      vpnEntries = "video.example",
    )
    val configured = JSONObject(
      ProfileSelection.applyApplications(
        config = withRoutes,
        mode = ProfileSelection.APPS_BYPASS,
        packages = setOf("com.example.bank"),
        vpnPackage = "app.veilark.android",
      ),
    )

    val routeRules = configured.getJSONObject("route").getJSONArray("rules").toString()
    assertTrue(routeRules.contains("bank.example"))
    assertTrue(routeRules.contains("video.example"))

    val tun = configured.getJSONArray("inbounds").getJSONObject(0)
    assertFalse(tun.has("include_package"))
    assertEquals(2, tun.getJSONArray("exclude_package").length())
    assertEquals("app.veilark.android", tun.getJSONArray("exclude_package").getString(0))
    assertEquals("com.example.bank", tun.getJSONArray("exclude_package").getString(1))
  }

  @Test
  fun switchingBackToFullTunnelRemovesOnlyDestinationRules() {
    val manual = ProfileSelection.applyRouting(
      config = baseConfig(),
      mode = ProfileSelection.ROUTING_MANUAL,
      directEntries = "direct.example",
      vpnEntries = "vpn.example",
    )
    val withApplicationSplit = ProfileSelection.applyApplications(
      config = manual,
      mode = ProfileSelection.APPS_ONLY,
      packages = setOf("com.example.browser"),
      vpnPackage = "app.veilark.android",
    )
    val fullTunnel = JSONObject(
      ProfileSelection.applyRouting(
        config = withApplicationSplit,
        mode = ProfileSelection.ROUTING_ALL,
      ),
    )

    assertFalse(fullTunnel.getJSONObject("route").has("rules"))
    assertTrue(
      fullTunnel.getJSONArray("inbounds").getJSONObject(0).has("include_package"),
    )
  }
}
