package com.example.veilark.vpn

import android.net.DnsResolver
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Time to complete a TCP handshake with an endpoint, used by the manual
 * latency checks. The name is resolved first and outside the measurement,
 * through the cancellable Android resolver: InetSocketAddress(host, port)
 * would resolve synchronously, without a timeout, and count the lookup as
 * network latency.
 */
internal object TcpConnectLatency {
  private const val RESOLVE_TIMEOUT_MS = 2_000L

  suspend fun measure(host: String, port: Int, connectTimeoutMs: Int): Int? {
    val address = resolve(host) ?: return null
    return connect(InetSocketAddress(address, port), connectTimeoutMs)
  }

  private suspend fun resolve(host: String): InetAddress? = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
      val signal = CancellationSignal()
      continuation.invokeOnCancellation { signal.cancel() }
      // A null network resolves like the process would connect, so the
      // address matches the path the connect below takes.
      DnsResolver.getInstance().query(
        null,
        host,
        DnsResolver.FLAG_EMPTY,
        Dispatchers.IO.asExecutor(),
        signal,
        object : DnsResolver.Callback<List<InetAddress>> {
          override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
            continuation.resume(answer.firstOrNull().takeIf { rcode == 0 })
          }

          override fun onError(error: DnsResolver.DnsException) {
            continuation.resume(null)
          }
        },
      )
    }
  }

  /** The blocking connect runs on an IO thread; cancellation closes the socket to end it. */
  private suspend fun connect(address: InetSocketAddress, timeoutMs: Int): Int? =
    suspendCancellableCoroutine { continuation ->
      val socket = Socket()
      continuation.invokeOnCancellation { runCatching { socket.close() } }
      Dispatchers.IO.asExecutor().execute {
        val started = SystemClock.elapsedRealtime()
        val connected = runCatching { socket.connect(address, timeoutMs) }.isSuccess
        val elapsed = (SystemClock.elapsedRealtime() - started).toInt()
        runCatching { socket.close() }
        // A cancelled continuation ignores the late result.
        continuation.resume(elapsed.takeIf { connected })
      }
    }
}
