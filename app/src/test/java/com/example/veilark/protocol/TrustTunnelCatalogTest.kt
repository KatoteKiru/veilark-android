package com.example.veilark.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class TrustTunnelCatalogTest {
  @Test
  fun catalogRoundTripPreservesMultipleEncryptedPayloadCandidates() {
    val expected = listOf(
      TrustTunnelCatalogEntry("de", "DE Frankfurt TrustTunnel", "[endpoint]\nname=\"DE\""),
      TrustTunnelCatalogEntry("nl", "NL Fast TrustTunnel", "[endpoint]\nname=\"NL\""),
    )

    assertEquals(expected, TrustTunnelCatalog.decode(TrustTunnelCatalog.encode(expected)))
  }
}
