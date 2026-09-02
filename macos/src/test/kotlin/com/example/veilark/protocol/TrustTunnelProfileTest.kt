package com.example.veilark.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustTunnelProfileTest {
  @Test
  fun forcesStableH2Ipv4TransportAndAntiDpi() {
    val optimized = TrustTunnelProfile.optimizeEndpoint(
      """
      [endpoint]
      upstream_protocol = "http2"
      has_ipv6 = true
      anti_dpi = false
      """.trimIndent(),
    )

    assertTrue("""upstream_protocol = "http2"""" in optimized)
    assertTrue("has_ipv6 = false" in optimized)
    assertTrue("anti_dpi = true" in optimized)
  }

  @Test
  fun compilesPublicLocalhostDeepLinkIntoCliConfig() {
    val profile = TrustTunnelProfile.compile(LOCALHOST_FIXTURE)

    assertEquals("localhost", profile.displayName)
    assertTrue("hostname = \"localhost\"" in profile.config)
    assertTrue("addresses = [\"127.0.0.1:4443\"]" in profile.config)
    assertTrue("username = \"test\"" in profile.config)
    assertTrue("custom_sni = \"localhost\"" in profile.config)
    assertTrue("anti_dpi = true" in profile.config)
    assertTrue("""upstream_protocol = "http2"""" in profile.config)
    assertTrue("has_ipv6 = false" in profile.config)
    assertTrue("-----BEGIN CERTIFICATE-----" in profile.config)
    assertTrue("killswitch_enabled = true" in profile.config)
    assertTrue("[listener.tun]" in profile.config)
    assertTrue("""included_routes = ["0.0.0.0/0"]""" in profile.config)
    assertTrue("2000::/3" !in profile.config)
  }

  @Test
  fun prepareMacConfigDropsIpv6DefaultRoute() {
    val sanitized = TrustTunnelProfile.prepareMacConfig(
      """
      has_ipv6 = false
      included_routes = ["0.0.0.0/0", "2000::/3"]
      client_random = ""
      """.trimIndent(),
    )
    assertTrue("""included_routes = ["0.0.0.0/0"]""" in sanitized)
    assertTrue("2000::/3" !in sanitized)
    assertTrue("client_random =" !in sanitized)
  }

  @Test
  fun acceptsLegacyQueryPrefix() {
    val withQuery = LOCALHOST_FIXTURE.replace("tt://", "tt://?")
    val profile = TrustTunnelProfile.compile(withQuery)
    assertEquals("localhost", profile.displayName)
    assertTrue("hostname = \"localhost\"" in profile.config)
  }

  @Test
  fun geoIpRuDirectUsesNativeExclusionsAndPreservesFullTunnelAndEndpointReachability() {
    val routed = TrustTunnelProfile.applyGeoIpRuDirect(
      TrustTunnelProfile.compile(LOCALHOST_FIXTURE).config,
      listOf("5.136.0.0/13", "2a00:f480::/29"),
    )

    assertTrue("vpn_mode = \"general\"" in routed)
    assertTrue("\"5.136.0.0/13\"" in routed)
    assertTrue("\"2a00:f480::/29\"" in routed)
    assertTrue("included_routes = [\"0.0.0.0/0\"]" in routed)
    assertTrue("has_ipv6 = false" in routed)
    assertTrue("2000::/3" !in routed)
    assertTrue("\"10.0.0.0/8\"" in routed)
    assertTrue("\"fc00::/7\"" in routed)
    assertTrue("\"127.0.0.1/32\"" in routed)
  }

  @Test
  fun geoRuDirectAlsoExcludesDomainsForSplitDns() {
    val routed = TrustTunnelProfile.applyGeoIpRuDirect(
      TrustTunnelProfile.compile(LOCALHOST_FIXTURE).config,
      listOf("5.136.0.0/13", "2a00:f480::/29"),
      listOf("example.ru", "service.example.ru"),
    )

    assertTrue("\"example.ru\"" in routed)
    assertTrue("\"service.example.ru\"" in routed)
    assertTrue("vpn_mode = \"general\"" in routed)
  }

  @Test
  fun geoRuDirectRejectsUnsupportedWildcardDomain() {
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      TrustTunnelProfile.applyGeoIpRuDirect(
        TrustTunnelProfile.compile(LOCALHOST_FIXTURE).config,
        listOf("5.136.0.0/13", "2a00:f480::/29"),
        listOf("*.example.ru"),
      )
    }
  }

  @Test
  fun fullTunnelPreparationRemainsIpv4Only() {
    val fullTunnel = TrustTunnelProfile.prepareMacConfig(TrustTunnelProfile.compile(LOCALHOST_FIXTURE).config)
    assertTrue("included_routes = [\"0.0.0.0/0\"]" in fullTunnel)
    assertTrue("2000::/3" !in fullTunnel)
  }

  @Test
  fun geoRoutingPreservesAnIpv6CapableListener() {
    val capable = TrustTunnelProfile.compile(LOCALHOST_FIXTURE).config
      .replace("has_ipv6 = false", "has_ipv6 = true")
      .replace(
        "included_routes = [\"0.0.0.0/0\"]",
        "included_routes = [\"0.0.0.0/0\", \"2000::/3\"]",
      )
    val routed = TrustTunnelProfile.applyGeoIpRuDirect(
      capable,
      listOf("5.136.0.0/13", "2a00:f480::/29"),
    )

    assertTrue("has_ipv6 = true" in routed)
    assertTrue("included_routes = [\"0.0.0.0/0\", \"2000::/3\"]" in routed)
  }

  companion object {
    // Public localhost fixture from TrustTunnelClient v1.0.49 instrumentation tests.
    private const val LOCALHOST_FIXTURE =
      "tt://AQlsb2NhbGhvc3QFBHRlc3QGBHRlc3QCDjEyNy4wLjAuMTo0NDQzCwRhYWJiAwlsb2NhbGhvc3QIQVMwggFPMIH1oAMCAQICFGi8WMY2yFmtW2u_18hMQBa0T4VGMAoGCCqGSM49BAMCMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDAeFw0yNjAxMzAwMDAwMDBaFw0yNzAxMzAwMDAwMDBaMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABB4ozK9KbqScCCTJ8CvTfW4W0r9OEsn4VcQswd-BbP9z-tdyfE5HT4uHLUSZRWUKfZjnRRkHWOwhp9KJhOE-LAWjJTAjMCEGA1UdEQQaMBiCCWxvY2FsaG9zdIILKi5sb2NhbGhvc3QwCgYIKoZIzj0EAwIDSQAwRgIhAO6gFBHDsgvWjPj39JNchcMF3X2ICgzycBwTyydxqpdiAiEAgybYwECuZopK1g6JX5tK0-5B3Of7n0NuPXRGSU5TtSc"
  }
}
