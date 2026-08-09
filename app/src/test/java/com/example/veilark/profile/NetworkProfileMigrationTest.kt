package com.example.veilark.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProfileMigrationTest {
  @Test
  fun upgradesAnExistingGeneratedProfileWithoutChangingRoutingRules() {
    val original = SubscriptionParser().compile(
      "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
        .toByteArray(),
    ).json
    val withRouting = ProfileSelection.applyRouting(
      original,
      ProfileSelection.ROUTING_MANUAL,
      directEntries = "example.ru",
      vpnEntries = "youtube.com",
    )
    val migrated = JSONObject(NetworkProfileMigration.migrate(withRouting))

    assertEquals(1280, migrated.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
    assertTrue(migrated.getJSONObject("dns").getBoolean("reverse_mapping"))
    assertEquals(
      "cloudflare-dns.com",
      migrated.getJSONObject("dns").getJSONArray("servers").getJSONObject(1)
        .getJSONObject("tls").getString("server_name"),
    )
    assertEquals(
      "https://cp.cloudflare.com/generate_204",
      migrated.getJSONArray("outbounds").getJSONObject(0).getString("url"),
    )
    assertEquals("ipv4_only", migrated.getJSONObject("dns").getString("strategy"))
    assertEquals(
      "youtube.com",
      migrated.getJSONObject("route").getJSONArray("rules").getJSONObject(1)
        .getJSONArray("domain_suffix").getString(0),
    )
  }

  @Test
  fun stableRecoveryRemovesBypassAndFragmentation() {
    val original = SubscriptionParser().compile(
      "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
        .toByteArray(),
    ).json
    var configured = ProfileSelection.applyRouting(
      original,
      ProfileSelection.ROUTING_MANUAL,
      directEntries = "example.ru",
      vpnEntries = "youtube.com",
    )
    configured = ProfileSelection.applyApplications(
      configured,
      ProfileSelection.APPS_BYPASS,
      setOf("com.android.chrome"),
    )
    configured = ProfileSelection.applyDpiProtection(
      configured,
      ProfileSelection.DPI_TLS_FRAGMENT,
    )

    val recovered = JSONObject(NetworkProfileMigration.recoverStableDefaults(configured))
    val tun = recovered.getJSONArray("inbounds").getJSONObject(0)
    assertFalse(tun.has("include_package"))
    assertFalse(tun.has("exclude_package"))
    assertFalse(recovered.getJSONObject("route").has("rules"))
    assertEquals("ipv4_only", recovered.getJSONObject("dns").getString("strategy"))
    assertFalse(
      recovered.getJSONArray("outbounds").getJSONObject(1)
        .getJSONObject("tls").has("fragment"),
    )
  }

  @Test
  fun reconciliationRemovesHiddenLegacyRulesAndRebuildsVisibleSettings() {
    val original = JSONObject(
      SubscriptionParser().compile(
        "trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL"
          .toByteArray(),
      ).json,
    )
    original.getJSONObject("route").put(
      "rules",
      org.json.JSONArray()
        .put(
          JSONObject()
            .put("rule_set", org.json.JSONArray().put("geoip-ru"))
            .put("outbound", "direct"),
        )
        .put(
          JSONObject()
            .put("package_name", org.json.JSONArray().put("com.openai.chatgpt"))
            .put("outbound", "direct"),
        ),
    )

    val reconciled = JSONObject(
      NetworkProfileMigration.reconcile(
        config = original.toString(),
        routingMode = ProfileSelection.ROUTING_MANUAL,
        directRoutes = "example.ru",
        vpnRoutes = "chatgpt.com gemini.google.com",
        applicationMode = ProfileSelection.APPS_ALL,
        selectedApplications = emptySet(),
        vpnPackage = "uk.senyasenyavski.veilark",
        dpiMode = ProfileSelection.DPI_OFF,
      ),
    )
    val serializedRules = reconciled.getJSONObject("route").getJSONArray("rules").toString()

    assertFalse(serializedRules.contains("geoip-ru"))
    assertFalse(serializedRules.contains("package_name"))
    assertTrue(serializedRules.contains("example.ru"))
    assertTrue(serializedRules.contains("chatgpt.com"))
    assertTrue(serializedRules.contains("gemini.google.com"))
  }
}
