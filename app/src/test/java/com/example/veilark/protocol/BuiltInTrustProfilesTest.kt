package com.example.veilark.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BuiltInTrustProfilesTest {
  @Test
  fun decodesDistinctTrustTunnelLinks() {
    assertEquals(
      listOf("tt://first", "tt://second"),
      BuiltInTrustProfiles.decode("tt://first\n\ntt://second\ntt://first\n"),
    )
  }

  @Test
  fun embeddedPayloadDigestIsStableAndContentSensitive() {
    assertEquals(
      BuiltInTrustProfiles.digest("tt://first"),
      BuiltInTrustProfiles.digest("tt://first"),
    )
    assertNotEquals(
      BuiltInTrustProfiles.digest("tt://first"),
      BuiltInTrustProfiles.digest("tt://second"),
    )
  }
}
