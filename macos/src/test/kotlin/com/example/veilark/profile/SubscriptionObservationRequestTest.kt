package com.example.veilark.profile

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class SubscriptionObservationRequestTest {
  private val id = "_" + "a".repeat(23)
  private fun headers(source: String): Map<String, String> = SubscriptionFetcher.deviceHeaders(source) { id }

  @Test fun realRequestBuilderSendsCompleteTupleOnlyOnInitialControlledRequest() = runBlocking {
    for (path in listOf("managed", "trust")) {
      val source = "https://sub.senyasenyavski.uk:2096/$path/abcdefghijklmnop"
      val requests = mutableListOf<FakeConnection>()
      val body = SubscriptionFetcher.fetchWithConnection(source, headers(source)) { uri ->
        FakeConnection(uri, if (requests.isEmpty()) 307 else 200).also(requests::add)
      }
      assertEquals("tt://synthetic", body.decodeToString())
      assertEquals(2, requests.size)
      assertEquals(id, requests[0].getRequestProperty("X-Veilark-Install-Id"))
      for (name in listOf("Device-Label", "Device-Model", "Platform", "App-Version")) {
        assertFalse(requests[0].getRequestProperty("X-Veilark-$name").isNullOrBlank())
      }
      assertNull(requests[1].getRequestProperty("X-Veilark-Install-Id"))
      assertNull(requests[1].getRequestProperty("X-Veilark-Device-Model"))
      assertEquals("third-party.example", requests[1].url.host)
    }
  }

  @Test fun thirdPartyDoesNotReceiveOrGenerateInstallationIdentity() {
    for (source in listOf(
      "https://third-party.example/trust/abcdefghijklmnop",
      "https://sub.senyasenyavski.uk.evil.example:2096/managed/abcdefghijklmnop"
    )) assertTrue(headers(source).isEmpty())
  }

  private class FakeConnection(uri: URI, private val statusCode: Int) : HttpURLConnection(uri.toURL()) {
    override fun connect() {}
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun getResponseCode() = statusCode
    override fun getHeaderField(name: String?): String? = when {
      name.equals("Location", true) -> "https://third-party.example/sub/result"
      name.equals("Content-Type", true) -> "text/plain"
      else -> null
    }
    override fun getInputStream() = ByteArrayInputStream("tt://synthetic".toByteArray())
  }
}

