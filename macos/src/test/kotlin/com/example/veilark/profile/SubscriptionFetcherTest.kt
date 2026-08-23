package com.example.veilark.profile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionFetcherTest {
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
}
