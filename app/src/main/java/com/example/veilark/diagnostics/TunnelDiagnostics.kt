package com.example.veilark.diagnostics

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

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

  suspend fun run() = runChecks(
    openConnection = { it.openConnection() as HttpURLConnection },
    clock = SystemClock::elapsedRealtime,
    log = { level, message ->
      when (level) {
        "error" -> TechnicalLogStore.error("DIAGNOSTICS", message)
        "warning" -> TechnicalLogStore.warning("DIAGNOSTICS", message)
        else -> TechnicalLogStore.info("DIAGNOSTICS", message)
      }
    },
  )

  internal suspend fun runChecks(
    openConnection: (URL) -> HttpURLConnection,
    clock: () -> Long,
    log: (String, String) -> Unit,
  ) = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    log("info", "External service check started")
    var answered = 0
    endpoints.forEach { endpoint ->
      currentCoroutineContext().ensureActive()
      val started = clock()
      try {
        val code = responseCode { openConnection(URL(endpoint.url)) }
        currentCoroutineContext().ensureActive()
        answered += 1
        val elapsed = clock() - started
        log(
          "info",
          "${endpoint.name} HTTPS=$code latency=${elapsed}ms",
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        // A disconnect caused by cancellation can surface as an IOException.
        currentCoroutineContext().ensureActive()
        val elapsed = clock() - started
        log(
          "error",
          "${endpoint.name} failed=${failure.javaClass.simpleName} latency=${elapsed}ms",
        )
      }
    }
    currentCoroutineContext().ensureActive()
    val level = if (answered == endpoints.size) {
      "Check completed: all ${endpoints.size} services responded"
    } else {
      "Check completed: $answered of ${endpoints.size} services responded"
    }
    if (answered == endpoints.size) {
      log("info", level)
    } else {
      log("warning", level)
    }
  }

  private suspend fun responseCode(openConnection: () -> HttpURLConnection): Int = coroutineScope {
    val active = AtomicReference<HttpURLConnection?>(null)
    val request = async(Dispatchers.IO) {
      currentCoroutineContext().ensureActive()
      val connection = openConnection()
      active.set(connection)
      try {
        currentCoroutineContext().ensureActive()
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "Veilark-Diagnostics/0.3")
        currentCoroutineContext().ensureActive()
        connection.responseCode
      } finally {
        if (active.compareAndSet(connection, null)) connection.disconnect()
      }
    }
    try {
      request.await()
    } finally {
      // await is cancellable even while the IO child is blocked in responseCode.
      // The scope then waits for that child, avoiding a detached request worker.
      active.getAndSet(null)?.let { connection ->
        try {
          connection.disconnect()
        } catch (failure: Exception) {
          currentCoroutineContext().ensureActive()
          throw failure
        }
      }
    }
  }
}
