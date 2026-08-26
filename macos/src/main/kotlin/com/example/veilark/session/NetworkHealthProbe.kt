package com.example.veilark.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

data class NetworkHealth(
  val reachable: Boolean,
  val detail: String,
)

object NetworkHealthProbe {
  suspend fun check(): NetworkHealth = withContext(Dispatchers.IO) {
    val dnsOk = runCatching { InetAddress.getByName("example.com") }.isSuccess
    val successes = coroutineScope {
      ENDPOINTS.map { endpoint ->
        async { endpoint to probe(endpoint) }
      }.awaitAll()
    }.filter { it.second }
    when {
      successes.isNotEmpty() && dnsOk -> NetworkHealth(
        reachable = true,
        detail = "DNS и HTTPS доступны",
      )
      successes.isNotEmpty() -> NetworkHealth(
        reachable = true,
        detail = "HTTPS доступен; системный DNS требует проверки",
      )
      else -> NetworkHealth(
        reachable = false,
        detail = if (dnsOk) "DNS доступен, HTTPS не отвечает" else "DNS и HTTPS не отвечают",
      )
    }
  }

  private fun probe(endpoint: String): Boolean = runCatching {
    val connection = URL(endpoint).openConnection() as HttpURLConnection
    try {
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 5_000
      connection.readTimeout = 5_000
      connection.setRequestProperty("User-Agent", "Veilark-macOS/0.2")
      connection.responseCode in 200..399
    } finally {
      connection.disconnect()
    }
  }.getOrDefault(false)

  private val ENDPOINTS = listOf(
    "https://cp.cloudflare.com/generate_204",
    "https://connectivitycheck.gstatic.com/generate_204",
    "https://www.apple.com/library/test/success.html",
  )
}
