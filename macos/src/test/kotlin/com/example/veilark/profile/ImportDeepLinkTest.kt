package com.example.veilark.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI
import java.net.URLEncoder

class ImportDeepLinkTest {
  @Test
  fun acceptsHttpsSubscription() {
    assertEquals(
      "https://provider.example/sub?token=opaque",
      ImportDeepLink.parseUri("veilark://import?url=https%3A%2F%2Fprovider.example%2Fsub%3Ftoken%3Dopaque"),
    )
    assertEquals(
      "https://provider.example/sub?token=opaque",
      ImportDeepLink.parse(URI("veilark://import?url=https%3A%2F%2Fprovider.example%2Fsub%3Ftoken%3Dopaque")),
    )
    assertNull(ImportDeepLink.parse(null))
  }

  @Test
  fun acceptsCaseInsensitiveSchemeHostAndSlashPath() {
    assertEquals(
      "https://provider.example/sub",
      ImportDeepLink.parseUri("VEILARK://IMPORT/?url=https%3A%2F%2Fprovider.example%2Fsub"),
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
  fun acceptsTrustTunnelQueryOnlyDeepLink() {
    // `trusttunnel_endpoint -f deeplink` produces `tt://?<base64url>` without a host.
    val link = "tt://?AAECAwQFBgcICQoLDA0ODw_-"
    assertEquals(link, ImportDeepLink.parseUri("veilark://import?url=" + encode(link)))
    assertEquals(link, ImportDeepLink.validatePayload(link))
    assertEquals("TT://?AAECAwQ", ImportDeepLink.validatePayload("TT://?AAECAwQ"))
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
      "file:///Users/profile.json",
      "https://user:password@provider.example/sub",
      "https://provider.example:99999/sub",
      "https://provider.example/sub#fragment",
      "https:///sub",
      "tt://user:password@opaque-profile",
      "tt://opaque-profile#fragment",
      "tt://",
      "tt://?",
      "tt://?AAECAwQ#fragment",
      "tt:///?AAECAwQ",
      "javascript:alert(1)",
      "data:text/plain,AAECAwQ",
      "not-a-profile",
      "",
    )

    values.forEach { value ->
      assertNull(
        "payload should be rejected: $value",
        ImportDeepLink.parseUri("veilark://import?url=" + encode(value)),
      )
    }
  }

  @Test
  fun rejectsWrongEnvelopeShapeAndExtraQueryParameters() {
    assertNull(ImportDeepLink.parseUri("veilark://other?url=https%3A%2F%2Fprovider.example%2Fsub"))
    assertNull(ImportDeepLink.parseUri("veilark://import/extra?url=https%3A%2F%2Fprovider.example%2Fsub"))
    assertNull(ImportDeepLink.parseUri("veilark://import:80?url=https%3A%2F%2Fprovider.example%2Fsub"))
    assertNull(ImportDeepLink.parseUri("veilark://import?url=https%3A%2F%2Fprovider.example%2Fsub#x"))
    assertNull(ImportDeepLink.parseUri("veilark://import?link=https%3A%2F%2Fprovider.example%2Fsub"))
    assertNull(ImportDeepLink.parseUri("veilark://import"))
    assertNull(ImportDeepLink.parseUri("https://import?url=https%3A%2F%2Fprovider.example%2Fsub"))
    assertNull(
      ImportDeepLink.parseUri("veilark://import?url=https%3A%2F%2Fprovider.example%2Fsub&extra=1"),
    )
    assertNull(
      ImportDeepLink.parseUri(
        "veilark://import?url=https%3A%2F%2Fprovider.example%2Fsub&url=https%3A%2F%2Fother.example%2Fsub",
      ),
    )
  }

  @Test
  fun rejectsOversizedPayload() {
    val prefix = "https://provider.example/sub?payload="
    val limit = "x".repeat(ImportDeepLink.MAX_PAYLOAD_LENGTH - prefix.length)
    assertEquals(prefix + limit, ImportDeepLink.validatePayload(prefix + limit))
    assertNull(ImportDeepLink.validatePayload(prefix + limit + "x"))
    assertNull(ImportDeepLink.validatePayload("tt://?" + "A".repeat(ImportDeepLink.MAX_PAYLOAD_LENGTH)))
  }

  @Test
  fun neverExposesTheInnerUrlThroughExceptions() {
    // The inner URL is a bearer credential: parsing must fail closed with null
    // instead of surfacing the value inside an exception message.
    val secret = "opaque-bearer-secret-6f1c"
    val inputs = listOf(
      "veilark://import?url=https%3A%2F%2Fprovider.example%2Fsub%3Ftoken%3D$secret%23fragment",
      "veilark://import?url=http%3A%2F%2Fprovider.example%2F$secret",
      "veilark://import?url=tt%3A%2F%2F%3F$secret%23fragment",
      "veilark://import?url=$secret%ZZ",
      "veilark://import?url=https://provider.example/$secret bad",
      "veilark://import?url=https%3A%2F%2Fprovider.example%2F$secret&url=1",
      "veilark://import?url=javascript%3Aalert(%27$secret%27)",
      "veilark://import#$secret",
      "veilark://$secret",
    )
    inputs.forEach { input ->
      val outcome = runCatching { ImportDeepLink.parseUri(input) }
      assertNull("must fail closed: $input", outcome.getOrNull())
      val message = outcome.exceptionOrNull()?.toString().orEmpty()
      assertFalse("exception must not carry the payload", message.contains(secret))
    }
  }

  private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
