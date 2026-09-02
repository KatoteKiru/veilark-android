package com.example.veilark.profile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFetcherTest {
  private val controlledHost = "subscriptions.example"
  private val controlledPort = 2096

  @Test
  fun acceptsSubscriptionPayload() {
    val body = "vless://profile".toByteArray()

    assertArrayEquals(
      body,
      SubscriptionFetcher.validateBody(body, "text/plain; charset=utf-8"),
    )
  }

  @Test
  fun rejectsHtmlByContentTypeOrBody() {
    val byType = assertThrows(IllegalArgumentException::class.java) {
      SubscriptionFetcher.validateBody("not html".toByteArray(), "text/html")
    }
    val byBody = assertThrows(IllegalArgumentException::class.java) {
      SubscriptionFetcher.validateBody(
        " \n<!DOCTYPE html><html></html>".toByteArray(),
        "text/plain",
      )
    }

    assertTrue(byType.message.orEmpty().contains("веб-страницу"))
    assertTrue(byBody.message.orEmpty().contains("веб-страницу"))
  }

  @Test
  fun rejectsEmptyResponse() {
    val failure = assertThrows(IllegalArgumentException::class.java) {
      SubscriptionFetcher.validateBody(byteArrayOf(), "text/plain")
    }

    assertTrue(failure.message.orEmpty().contains("пустую"))
  }

  @Test
  fun sendsDeviceHeadersOnlyToConfiguredManagedAndTrustPaths() {
    assertTrue(
      SubscriptionFetcher.isVeilarkControlledEndpoint(
        "https://subscriptions.example:2096/managed/abcdefghijklmnop",
        controlledHost,
        controlledPort,
      ),
    )
    assertTrue(
      SubscriptionFetcher.isVeilarkControlledEndpoint(
        "https://subscriptions.example:2096/trust/abcdefghijklmnop",
        controlledHost,
        controlledPort,
      ),
    )
  }

  @Test
  fun neverTreatsThirdPartyOrLookalikePathsAsControlled() {
    val values = listOf(
      "https://third-party.example/managed/client",
      "https://subscriptions.example/managed/abcdefghijklmnop",
      "https://subscriptions.example:2097/managed/abcdefghijklmnop",
      "https://subscriptions.example/managedness/client",
      "https://subscriptions.example/public/trust/client",
      "https://subscriptions.example:2096/trust/short",
      "https://subscriptions.example:2096/trust/abcdefghijklmnop/extra",
      "https://subscriptions.example:2096/trust/abcdefghijklmnop?copy=1",
      "http://subscriptions.example/managed/client",
      "https://user:password@subscriptions.example/trust/client",
    )
    values.forEach { value ->
      assertFalse(
        "third-party or unsafe endpoint must not receive device headers: $value",
        SubscriptionFetcher.isVeilarkControlledEndpoint(value, controlledHost, controlledPort),
      )
    }
  }

  @Test
  fun stripsVeilarkObservationHeadersAfterAnyRedirect() {
    val headers = mapOf(
      "X-Veilark-Install-Id" to "opaque-install",
      "X-Veilark-Platform" to "android",
      "Accept-Language" to "ru",
    )

    assertEquals(headers, SubscriptionFetcher.headersForHop(headers, 0))
    assertEquals(
      mapOf("Accept-Language" to "ru"),
      SubscriptionFetcher.headersForHop(headers, 1),
    )
  }
}
