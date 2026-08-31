package com.example.veilark.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportDeepLinkTest {
  @Test
  fun acceptsHttpsSubscriptionOnColdOrWarmViewIntent() {
    assertEquals(
      "https://provider.example/sub?token=opaque",
      ImportDeepLink.parseUri("veilark://import?url=https%3A%2F%2Fprovider.example%2Fsub%3Ftoken%3Dopaque"),
    )
  }

  @Test
  fun acceptsTrustTunnelPayload() {
    assertEquals(
      "tt://opaque-profile",
      ImportDeepLink.parseUri("veilark://import?url=tt%3A%2F%2Fopaque-profile"),
    )
  }

  @Test
  fun acceptsHttpsSubscriptionOnAnExplicitPort() {
    assertEquals(
      "https://provider.example:2096/sub?token=opaque",
      ImportDeepLink.parseUri(
        "veilark://import?url=https%3A%2F%2Fprovider.example%3A2096%2Fsub%3Ftoken%3Dopaque",
      ),
    )
  }

  @Test
  fun rejectsUnsafeOrMalformedPayloads() {
    val values = listOf(
      "http://provider.example/sub",
      "file:///data/local/tmp/profile",
      "https://user:password@provider.example/sub",
      "https://provider.example:99999/sub",
      "https://provider.example/sub#fragment",
      "tt://user:password@opaque-profile",
      "tt://opaque-profile#fragment",
      "tt://",
      "not-a-profile",
    )

    values.forEach { value ->
      val encoded = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
      assertNull(
        "payload should be rejected: $value",
        ImportDeepLink.parseUri("veilark://import?url=$encoded"),
      )
    }
  }

  @Test
  fun rejectsWrongIntentShapeAndExtraQueryParameters() {
    assertNull(ImportDeepLink.parseUri("veilark://other?url=https%3A%2F%2Fprovider.example%2Fsub"))
    assertNull(
      ImportDeepLink.parseUri("veilark://import?url=https%3A%2F%2Fprovider.example%2Fsub&extra=1"),
    )
  }

  @Test
  fun rejectsOversizedPayload() {
    val value = "https://provider.example/sub?payload=" + "x".repeat(ImportDeepLinkTestConstants.MAX_PAYLOAD_LENGTH)
    assertNull(ImportDeepLink.validatePayload(value))
  }

  private object ImportDeepLinkTestConstants {
    const val MAX_PAYLOAD_LENGTH = 8 * 1024
  }
}
