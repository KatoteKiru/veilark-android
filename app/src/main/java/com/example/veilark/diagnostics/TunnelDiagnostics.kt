package com.example.veilark.diagnostics

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object TunnelDiagnostics {
  private data class Endpoint(
    val name: String,
    val url: String,
  )

  private val endpoints = listOf(
    Endpoint("Cloudflare", "https://cp.cloudflare.com/generate_204"),
    Endpoint("YouTube", "https://www.youtube.com/generate_204"),
    Endpoint("Google", "https://www.google.com/generate_204"),
    Endpoint("GitHub", "https://github.com/"),
    Endpoint("Wikipedia", "https://www.wikipedia.org/"),
  )

  suspend fun run() = withContext(Dispatchers.IO) {
    TechnicalLogStore.info("DIAGNOSTICS", "Запущена проверка внешних сервисов")
    var answered = 0
    endpoints.forEach { endpoint ->
      val started = SystemClock.elapsedRealtime()
      runCatching {
        val connection = URL(endpoint.url).openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = 10_000
          connection.readTimeout = 10_000
          connection.instanceFollowRedirects = false
          connection.useCaches = false
          connection.setRequestProperty("User-Agent", "Veilark-Diagnostics/0.3")
          connection.responseCode
        } finally {
          connection.disconnect()
        }
      }.onSuccess { code ->
        answered += 1
        val elapsed = SystemClock.elapsedRealtime() - started
        TechnicalLogStore.info(
          "DIAGNOSTICS",
          "${endpoint.name} HTTPS=$code latency=${elapsed}ms",
        )
      }.onFailure { failure ->
        val elapsed = SystemClock.elapsedRealtime() - started
        TechnicalLogStore.error(
          "DIAGNOSTICS",
          "${endpoint.name} failed=${failure.javaClass.simpleName} latency=${elapsed}ms",
        )
      }
    }
    val level = if (answered == endpoints.size) {
      "Проверка завершена: ответили все ${endpoints.size} сервисов"
    } else {
      "Проверка завершена: ответили $answered из ${endpoints.size} сервисов"
    }
    if (answered == endpoints.size) {
      TechnicalLogStore.info("DIAGNOSTICS", level)
    } else {
      TechnicalLogStore.warning("DIAGNOSTICS", level)
    }
  }
}
