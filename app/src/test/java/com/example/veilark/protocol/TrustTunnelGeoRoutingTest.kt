package com.example.veilark.protocol

import com.example.veilark.profile.ProfileSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class TrustTunnelGeoRoutingTest {
  @Test
  fun parsesAndDeduplicatesIpv4AndIpv6Prefixes() {
    val payload = """
      {
        "version": 1,
        "rules": [
          {"ip_cidr": ["203.0.113.0/24", "2001:db8::/32"]},
          {"ip_cidr": ["203.0.113.0/24"]}
        ]
      }
    """.trimIndent()

    assertEquals(
      listOf("203.0.113.0/24", "2001:db8::/32"),
      TrustTunnelGeoRouting.parseRuCidrs(payload),
    )
  }

  @Test
  fun rejectsMalformedPrefixInsteadOfCreatingAnUnsafeConfig() {
    val payload = """{"rules":[{"ip_cidr":["203.0.113.0/33"]}]}"""

    assertThrows(IllegalArgumentException::class.java) {
      TrustTunnelGeoRouting.parseRuCidrs(payload)
    }
  }

  @Test
  fun validatesAddressesWithoutDnsResolution() {
    assertEquals(true, TrustTunnelGeoRouting.isValidCidr("203.0.113.0/24"))
    assertEquals(true, TrustTunnelGeoRouting.isValidCidr("2001:db8::/32"))
    assertEquals(false, TrustTunnelGeoRouting.isValidCidr("999.1.1.1/24"))
    assertEquals(false, TrustTunnelGeoRouting.isValidCidr("not-a-host.example/24"))
  }

  @Test
  fun packagedDatasetMatchesPinnedProvenance() {
    val asset = File("src/main/assets/rules/geoip-ru.json")
    assertEquals(true, asset.isFile)
    val bytes = asset.readBytes()
    val fullHash = MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { byte -> "%02x".format(byte) }
    assertEquals("ad921e489713e5a837417e3a0f5ed5ce07d9f8ffe9aeecf47c13ee397bff0d2b", fullHash)
    assertEquals(
      TrustTunnelGeoRouting.EXPECTED_CIDR_COUNT,
      TrustTunnelGeoRouting.parseRuCidrs(bytes.toString(Charsets.UTF_8)).size,
    )
  }

  @Test
  fun startupConfigRemovesGeoExclusionsUntilTheCoreIsConnected() {
    val config = """
      vpn_mode = "general"
      exclusions = ["203.0.113.0/24"]

      [endpoint]
      addresses = ["example.com:443"]
    """.trimIndent()

    assertEquals(
      true,
      TrustTunnelGeoRouting.startupConfig(config).contains("exclusions = []"),
    )
  }

  @Test
  fun onlyRussiaDirectModeRequestsTheTemporarilyDisabledGeoBypass() {
    assertEquals(
      true,
      TrustTunnelGeoRouting.isRussiaDirectMode(ProfileSelection.ROUTING_RU_DIRECT),
    )
    assertEquals(
      false,
      TrustTunnelGeoRouting.isRussiaDirectMode(ProfileSelection.ROUTING_ALL),
    )
    assertEquals(
      false,
      TrustTunnelGeoRouting.isRussiaDirectMode(ProfileSelection.ROUTING_MANUAL),
    )
  }
}
