package com.example.veilark.protocol

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adguard.trusttunnel.VpnServiceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrustTunnelImportInstrumentedTest {
  init {
    System.loadLibrary("trusttunnel_android")
  }

  @Test
  fun compilesAnUpstreamCompatibleDeepLinkWithTheNativeRuntime() {
    val profile = TrustTunnelProfile.compile(LOCALHOST_FIXTURE)

    // TrustTunnel 1.1.5 does not emit the fixture host as an endpoint name.
    // Veilark deliberately applies its stable fallback instead of guessing.
    assertEquals("TrustTunnel", profile.displayName)
    assertTrue("anti_dpi = true" in profile.config)
    assertTrue("upstream_protocol = \"http2\"" in profile.config)
    assertTrue("has_ipv6 = false" in profile.config)
    assertFalse("anti_dpi = false" in profile.config)
    assertNotNull(VpnServiceConfig.parseToml(profile.config))
  }

  @Test
  fun compilesGeoIpRuCidrsAsNativeDirectExclusions() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val cidrs = TrustTunnelGeoRouting.ruCidrs(context)
    assertEquals(10_859, cidrs.size)
    assertTrue(cidrs.any { it == "2.56.24.0/22" })
    assertTrue(cidrs.any { it == "2a14:cf00::/29" })

    val base = TrustTunnelProfile.compile(LOCALHOST_FIXTURE)
    assertTrue("exclusions = []" in base.config)
    val effective = TrustTunnelProfile.withDirectCidrs(base.config, cidrs)
    assertTrue("vpn_mode = \"general\"" in effective)
    assertTrue("exclusions = [\"2.56.24.0/22\"" in effective)
    assertNotNull(VpnServiceConfig.parseToml(effective))
  }

  companion object {
    // Public localhost fixture from TrustTunnelClient instrumentation tests.
    private const val LOCALHOST_FIXTURE =
      "tt://AQlsb2NhbGhvc3QFBHRlc3QGBHRlc3QCDjEyNy4wLjAuMTo0NDQzCwRhYWJiAwlsb2NhbGhvc3QIQVMwggFPMIH1oAMCAQICFGi8WMY2yFmtW2u_18hMQBa0T4VGMAoGCCqGSM49BAMCMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDAeFw0yNjAxMzAwMDAwMDBaFw0yNzAxMzAwMDAwMDBaMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABB4ozK9KbqScCCTJ8CvTfW4W0r9OEsn4VcQswd-BbP9z-tdyfE5HT4uHLUSZRWUKfZjnRRkHWOwhp9KJhOE-LAWjJTAjMCEGA1UdEQQaMBiCCWxvY2FsaG9zdIILKi5sb2NhbGhvc3QwCgYIKoZIzj0EAwIDSQAwRgIhAO6gFBHDsgvWjPj39JNchcMF3X2ICgzycBwTyydxqpdiAiEAgybYwECuZopK1g6JX5tK0-5B3Of7n0NuPXRGSU5TtSc"
  }
}
