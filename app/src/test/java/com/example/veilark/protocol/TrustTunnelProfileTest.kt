package com.example.veilark.protocol

import com.adguard.trusttunnel.VpnServiceConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
  fun addsDirectCidrsToNativeGeneralModeExclusionsWithoutChangingTunRoutes() {
    val config = TrustTunnelProfile.buildConfig(
      endpoint = """
        [endpoint]
        name = "Test"
      """.trimIndent(),
      directCidrs = listOf("203.0.113.0/24", "2001:db8::/32", "203.0.113.0/24"),
    )

    assertTrue("vpn_mode = \"general\"" in config)
    assertTrue("exclusions = [\"203.0.113.0/24\", \"2001:db8::/32\"]" in config)
    assertTrue("included_routes = [\"0.0.0.0/0\", \"2000::/3\"]" in config)
    assertTrue("excluded_routes = [\"0.0.0.0/8\"" in config)
    assertFalse("geoip-ru.srs" in config)
  }

  @Test
  fun replacesPreviousDirectCidrsWhenRoutingModeChanges() {
    val geo = TrustTunnelProfile.buildConfig(
      endpoint = """
        [endpoint]
        name = "Test"
      """.trimIndent(),
      directCidrs = listOf("203.0.113.0/24"),
    )
    val full = TrustTunnelProfile.withDirectCidrs(geo, emptyList())

    assertTrue("exclusions = []" in full)
    assertFalse("203.0.113.0/24" in full)
  }

  @Test
  fun acceptsValidatedDomainExclusionsForManualRouting() {
    val config = TrustTunnelProfile.buildConfig(
      endpoint = "[endpoint]\nname = \"Test\"",
    )

    val manual = TrustTunnelProfile.withDirectExclusions(
      config,
      listOf("example.ru", "*.example.ru", "10.0.0.0/8"),
    )

    assertTrue("exclusions = [\"example.ru\", \"*.example.ru\", \"10.0.0.0/8\"]" in manual)
  }

  @Test
  fun stableCoreAcceptsExistingProfilesWithoutRecoveryMigration() {
    val config = TrustTunnelProfile.buildConfig(
      endpoint = "[endpoint]\nname = \"Test\"",
    )

    assertNotNull(VpnServiceConfig.parseToml(config))
  }

  companion object {
    private const val LOCALHOST_FIXTURE =
      "tt://AQlsb2NhbGhvc3QFBHRlc3QGBHRlc3QCDjEyNy4wLjAuMTo0NDQzCwRhYWJiAwlsb2NhbGhvc3QIQVMwggFPMIH1oAMCAQICFGi8WMY2yFmtW2u_18hMQBa0T4VGMAoGCCqGSM49BAMCMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDAeFw0yNjAxMzAwMDAwMDBaFw0yNzAxMzAwMDAwMDBaMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABB4ozK9KbqScCCTJ8CvTfW4W0r9OEsn4VcQswd-BbP9z-tdyfE5HT4uHLUSZRWUKfZjnRRkHWOwhp9KJhOE-LAWjJTAjMCEGA1UdEQQaMBiCCWxvY2FsaG9zdIILKi5sb2NhbGhvc3QwCgYIKoZIzj0EAwIDSQAwRgIhAO6gFBHDsgvWjPj39JNchcMF3X2ICgzycBwTyydxqpdiAiEAgybYwECuZopK1g6JX5tK0-5B3Of7n0NuPXRGSU5TtSc"
  }
}
