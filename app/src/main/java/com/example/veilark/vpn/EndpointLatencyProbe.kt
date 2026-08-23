package com.example.veilark.vpn

import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.profile.ConnectionNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
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

  fun refresh(config: String, nodes: List<ConnectionNode>) {
    if (mutableChecking.value) return
    scope.launch {
      mutableChecking.value = true
      try {
        val endpoints = parseEndpoints(config, nodes)
        val semaphore = Semaphore(PARALLELISM)
        mutableLatencies.value = endpoints.map { endpoint ->
          async {
            semaphore.withPermit {
              measure(endpoint)?.let { endpoint.tag to it }
            }
          }
        }.awaitAll().filterNotNull().toMap()
        TechnicalLogStore.info(
          "PING",
          "TCP endpoints checked: ${endpoints.size}; responded: ${mutableLatencies.value.size}",
        )
      } catch (failure: Throwable) {
        TechnicalLogStore.error("PING", "Endpoint check failed: ${failure.javaClass.simpleName}")
      } finally {
        mutableChecking.value = false
      }
    }
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

  private fun measure(endpoint: Endpoint): Int? {
    var connected = false
    val elapsed = measureTimeMillis {
      connected = runCatching {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(endpoint.host, endpoint.port), TIMEOUT_MS)
        }
        true
      }.getOrDefault(false)
    }
    return elapsed.toInt().takeIf { connected }
  }

  private data class Endpoint(
    val tag: String,
    val host: String,
    val port: Int,
  )
}
