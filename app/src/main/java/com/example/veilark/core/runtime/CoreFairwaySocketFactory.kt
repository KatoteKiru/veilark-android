package com.example.veilark.core.runtime

import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Actual kernel factories, dormant until a real common native host is wired. */
internal class CoreFairwaySocketFactory(
  private val tcpConnectLimitMillis: Int = 5000,
) : CoreFairwaySocketOpener {
  init { require(tcpConnectLimitMillis in 1..30_000) }

  override fun operation(request: CoreFairwaySocketRequest): CoreFairwaySocketOperation = Task(request)

  private inner class Task(private val request: CoreFairwaySocketRequest) : CoreFairwaySocketOperation {
    private val cancelled = AtomicBoolean(false)
    private val tcp = if (request.protocol == CoreFairwaySocketProtocol.TCP) CoreFairwayTcpConnect() else null
    override fun cancel() { cancelled.set(true); tcp?.cancel() }

    override fun deliver(host: CoreFairwaySocketHost, active: () -> Boolean): CoreFairwayFdFailure? {
      check(Looper.myLooper() != Looper.getMainLooper()) { "Fairway sockets require an IO worker" }
      fun current() = !cancelled.get() && active() && host.isCurrent(request)
      fun failed(reason: CoreFairwayFdFailureReason) = CoreFairwayFdFailure(reason)
      if (!current()) return failed(CoreFairwayFdFailureReason.STALE)
      val binding = host.network(request) ?: return failed(CoreFairwayFdFailureReason.NETWORK_UNAVAILABLE)
      if (!binding.isUsable()) return failed(CoreFairwayFdFailureReason.NETWORK_UNAVAILABLE)
      if (request.protocol == CoreFairwaySocketProtocol.UDP) {
        return CoreFairwayUdpSocketFactory().deliverAuthorized(binding, request.destinationIpv4,
          request.destinationPort, ::current) { host.deliverBorrowed(request, it) }
      }
      var protectionFailed = false
      val fenced = object : CoreFairwayNetworkBinding {
        override fun isUsable() = current() && binding.isUsable()
        override fun bind(fd: FileDescriptor) {
          if (!current()) throw CancellationException("Stale Fairway TCP request")
          binding.bind(fd)
        }
        override fun protect(fd: Int): Boolean {
          if (!current()) throw CancellationException("Stale Fairway TCP request")
          return binding.protect(fd).also { protectionFailed = !it }
        }
      }
      val ip = request.destinationIpv4
      val peer = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(
        (ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte(),
      )), request.destinationPort)
      var original: ParcelFileDescriptor? = null
      try {
        // Java limit bounds resources; native expiry remains authoritative.
        // No absolute Rust Instant/elapsedRealtime conversion is invented.
        val connected = checkNotNull(tcp).connect(fenced, peer, tcpConnectLimitMillis)
        original = connected
        if (!current()) return failed(CoreFairwayFdFailureReason.STALE)
        val code = host.deliverBorrowed(request, connected.fd)
        check(code <= 0) { "Invalid Fairway FD admission result" }
        return if (code == 0) null else CoreFairwayFdFailure(CoreFairwayFdFailureReason.NATIVE_REJECTED, code)
      } catch (_: CancellationException) {
        return failed(CoreFairwayFdFailureReason.STALE)
      } catch (_: IOException) {
        return failed(if (protectionFailed) CoreFairwayFdFailureReason.PROTECTION_FAILED else CoreFairwayFdFailureReason.IO_FAILURE)
      } catch (_: ErrnoException) {
        return failed(CoreFairwayFdFailureReason.IO_FAILURE)
      } finally {
        // Cleanup failure must propagate, rather than masquerade as an opener
        // rejection after native may already have accepted its duplicate.
        original?.close()
      }
    }
  }
}
