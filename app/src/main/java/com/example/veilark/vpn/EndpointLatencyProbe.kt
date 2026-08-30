package com.example.veilark.vpn

import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.profile.ConnectionNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.system.measureTimeMillis

/**
 * A manual, pre-connect reachability check for TCP-based sing-box outbounds.
 * UDP/QUIC outbounds are intentionally omitted because a UDP connect call
 * does not provide a real round-trip measurement.
 */
object EndpointLatencyProbe {
  private const val PARALLELISM = 6
  private const val TIMEOUT_MS = 2_000
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutableLatencies = MutableStateFlow<Map<String, Int>>(emptyMap())
  val latencies = mutableLatencies.asStateFlow()
  private val mutableChecking = MutableStateFlow(false)
  val checking = mutableChecking.asStateFlow()
  private var refreshJob: Job? = null
  private var refreshGeneration = 0L

  @Synchronized
  fun refresh(config: String, nodes: List<ConnectionNode>) {
    refreshJob?.cancel()
    val generation = ++refreshGeneration
    mutableChecking.value = true
    refreshJob = scope.launch {
      mutableChecking.value = true
      try {
        val endpoints = ManualLatencyPolicy.boundedTargets(parseEndpoints(config, nodes))
        val measured = withTimeoutOrNull(ManualLatencyPolicy.DEADLINE_MS) {
          coroutineScope {
            val semaphore = Semaphore(PARALLELISM)
            endpoints.map { endpoint ->
              async {
                semaphore.withPermit {
                  measure(endpoint)?.let { endpoint.tag to it }
                }
              }
            }.awaitAll().filterNotNull().toMap()
          }
        }.orEmpty()
        if (generation == refreshGeneration) mutableLatencies.value = measured
        TechnicalLogStore.info(
          "PING",
          "TCP endpoints checked: ${endpoints.size}; responded: ${measured.size}",
        )
      } catch (failure: Throwable) {
        if (generation == refreshGeneration) {
          TechnicalLogStore.error("PING", "Endpoint check failed: ${failure.javaClass.simpleName}")
        }
      } finally {
        synchronized(this@EndpointLatencyProbe) {
          if (generation == refreshGeneration) {
            mutableChecking.value = false
            refreshJob = null
          }
        }
      }
    }
  }

  @Synchronized
  fun stop() {
    refreshGeneration += 1L
    refreshJob?.cancel()
    refreshJob = null
    mutableChecking.value = false
  }

  private fun parseEndpoints(
    config: String,
    nodes: List<ConnectionNode>,
  ): List<Endpoint> {
    val acceptedTags = nodes
      .filterNot { it.protocol.contains("Hysteria", ignoreCase = true) }
      .map(ConnectionNode::tag)
      .toSet()
    val outbounds = JSONObject(config).getJSONArray("outbounds")
    return buildList {
      repeat(outbounds.length()) { index ->
        val outbound = outbounds.optJSONObject(index) ?: return@repeat
        val tag = outbound.optString("tag")
        if (tag !in acceptedTags) return@repeat
        val host = outbound.optString("server").takeIf(String::isNotBlank) ?: return@repeat
        val port = outbound.optInt("server_port").takeIf { it in 1..65535 } ?: return@repeat
        add(Endpoint(tag, host, port))
      }
    }
  }

  private suspend fun measure(endpoint: Endpoint): Int? =
    suspendCancellableCoroutine { continuation ->
      val socket = Socket()
      continuation.invokeOnCancellation { runCatching { socket.close() } }
      var connected = false
      val elapsed = measureTimeMillis {
        connected = runCatching {
          socket.connect(InetSocketAddress(endpoint.host, endpoint.port), TIMEOUT_MS)
          true
        }.getOrDefault(false)
      }
      runCatching { socket.close() }
      if (continuation.isActive) {
        continuation.resume(elapsed.toInt().takeIf { connected })
      }
    }

  private data class Endpoint(
    val tag: String,
    val host: String,
    val port: Int,
  )
}
