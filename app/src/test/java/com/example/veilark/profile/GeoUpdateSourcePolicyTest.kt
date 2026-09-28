package com.example.veilark.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeoUpdateSourcePolicyTest {
  @Test
  fun allowsOnlyExactProductionMirrorAndUpstreamAuthorities() {
    assertEquals(
      "sub.senyasenyavski.uk",
      GeoUpdateSourcePolicy.requireAllowed(
        "https://sub.senyasenyavski.uk/veilark/geo/current/manifest.json",
      ).host,
    )
    assertEquals(
      2096,
      GeoUpdateSourcePolicy.requireAllowed(
        "https://nl2.senyasenyavski.uk:2096/veilark/geo/current/manifest.json",
      ).port,
    )
    assertEquals(
      "raw.githubusercontent.com",
      GeoUpdateSourcePolicy.requireAllowed(
        "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs",
      ).host,
    )
  }

  @Test
  fun rejectsLookalikesWrongPortsAndUrlCredentials() {
    listOf(
      "http://sub.senyasenyavski.uk/veilark/geo/current/manifest.json",
      "https://sub.senyasenyavski.uk:2096/veilark/geo/current/manifest.json",
      "https://sub.senyasenyavski.uk.evil.example/veilark/geo/current/manifest.json",
      "https://nl2.senyasenyavski.uk/veilark/geo/current/manifest.json",
      "https://user@raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs",
      "https://raw.githubusercontent.com:443/SagerNet/sing-geoip/rule-set/geoip-ru.srs",
    ).forEach { url ->
      assertThrows(IllegalArgumentException::class.java) {
        GeoUpdateSourcePolicy.requireAllowed(url)
      }
    }
  }
}
