package com.example.veilark.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubscriptionIdentityTest {
  @Test
  fun equivalentHttpsUrlsUseSameSourceIdAndIgnoreFragment() {
    val first = SubscriptionIdentity.sourceId(
      SubscriptionKind.SING_BOX,
      "HTTPS://Provider.Example/sub?id=1#screen",
      "ignored",
    )
    val second = SubscriptionIdentity.sourceId(
      SubscriptionKind.SING_BOX,
      "https://provider.example/sub?id=1",
      "also ignored",
    )

    assertEquals(first, second)
  }

  @Test
  fun protocolKindsCannotCollideForSameUrl() {
    val sing = SubscriptionIdentity.sourceId(
      SubscriptionKind.SING_BOX,
      "https://provider.example/sub",
      "ignored",
    )
    val trust = SubscriptionIdentity.sourceId(
      SubscriptionKind.TRUST_TUNNEL,
      "https://provider.example/sub",
      "ignored",
    )

    assertNotEquals(sing, trust)
  }

  @Test(expected = IllegalArgumentException::class)
  fun remoteIdentityRejectsPlainHttp() {
    SubscriptionIdentity.sourceId(
      SubscriptionKind.SING_BOX,
      "http://provider.example/sub",
      "ignored",
    )
  }
}
