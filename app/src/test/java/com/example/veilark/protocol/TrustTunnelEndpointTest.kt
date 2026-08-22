package com.example.veilark.protocol

import org.junit.Assert.assertNotNull
import org.junit.Test

class TrustTunnelEndpointTest {
  @Test
  fun profileWithAddressesRemainsValidAfterOptimization() {
    val endpoint = """
      [endpoint]
      hostname = "vpn.example.com"
      addresses = ["192.0.2.10:443", "[2001:db8::1]:443"]
      upstream_protocol = "http2"
    """.trimIndent()

    val optimized = TrustTunnelProfile.optimizeEndpoint(endpoint)

    assertNotNull(Regex("""addresses\s*=\s*\[""").find(optimized))
  }
}
