package com.example.veilark.protocol

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
}
