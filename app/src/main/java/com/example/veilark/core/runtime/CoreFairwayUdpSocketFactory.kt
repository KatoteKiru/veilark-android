package com.example.veilark.core.runtime

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A specific non-VPN Network, captured by the attempt's network-epoch owner. */
internal class CoreFairwayAndroidNetworkBinding(
  private val connectivity: ConnectivityManager,
  private val network: Network,
  private val protectSocket: (Int) -> Boolean,
) : CoreFairwayNetworkBinding {
  override fun isUsable(): Boolean {
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
      !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
  }

  override fun bind(fd: FileDescriptor) = network.bindSocket(fd)
  override fun protect(fd: Int): Boolean = protectSocket(fd)
  override suspend fun resolveIpv4(host: String): List<java.net.Inet4Address> {
    check(isUsable()) { "Captured network unavailable" }
    return suspendCancellableCoroutine { continuation ->
      val cancellation = CancellationSignal()
      continuation.invokeOnCancellation { cancellation.cancel() }
      if (continuation.isActive) try {
        DnsResolver.getInstance().query(network, host, DnsResolver.TYPE_A, DnsResolver.FLAG_EMPTY,
          java.util.concurrent.Executor { it.run() }, cancellation,
          object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
              if (!continuation.isActive) return
              if (rcode != 0) continuation.resumeWithException(IOException("Captured network lookup failed"))
              else continuation.resume(answer.asSequence().filterIsInstance<java.net.Inet4Address>().take(16).toList())
            }
            override fun onError(error: DnsResolver.DnsException) {
              if (continuation.isActive) continuation.resumeWithException(IOException("Captured network lookup failed"))
            }
          })
      } catch (_: Exception) {
        if (continuation.isActive) continuation.resumeWithException(IOException("Captured network lookup failed"))
      }
    }
  }
}

/**
 * Disabled Fairway integration component: not called by the released VPN service.
 * The broker runs this synchronous, nonblocking-socket work on bounded IO workers.
 * Native must revalidate the entire request and duplicate the BORROWED fd inside
 * deliverBorrowed; Java closes its original even on rejection or cancellation.
 */
internal class CoreFairwayUdpSocketFactory : CoreFairwayFdOpener {
  override fun deliver(
    request: CoreFairwayFdRequest,
    host: CoreFairwayFdHost,
    isActive: () -> Boolean,
  ): CoreFairwayFdFailure? {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Fairway socket creation requires an IO worker" }
    fun current(): Boolean = isActive() && host.isCurrent(request)
    fun failed(reason: CoreFairwayFdFailureReason) = CoreFairwayFdFailure(reason)
    if (!current()) return failed(CoreFairwayFdFailureReason.STALE)
    val binding = host.network(request) ?: return failed(CoreFairwayFdFailureReason.NETWORK_UNAVAILABLE)
    if (!binding.isUsable()) return failed(CoreFairwayFdFailureReason.NETWORK_UNAVAILABLE)
    return deliverAuthorized(binding, request.destinationIpv4, request.destinationPort, ::current) {
      host.deliverBorrowed(request, it)
    }
  }

  /** Shared kernel UDP operation; caller authorizes the full typed request. */
  internal fun deliverAuthorized(
    binding: CoreFairwayNetworkBinding,
    destinationIpv4: Int,
    destinationPort: Int,
    current: () -> Boolean,
    deliverBorrowed: (Int) -> Int,
  ): CoreFairwayFdFailure? {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Fairway socket creation requires an IO worker" }
    fun failed(reason: CoreFairwayFdFailureReason) = CoreFairwayFdFailure(reason)
    if (!current()) return failed(CoreFairwayFdFailureReason.STALE)
    require(destinationPort in 1..65535)
    var owned: ParcelFileDescriptor? = null
    try {
      val raw = Os.socket(OsConstants.AF_INET,
        OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC,
        OsConstants.IPPROTO_UDP)
      try { owned = ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
      val socket = checkNotNull(owned)
      if (!current()) return failed(CoreFairwayFdFailureReason.STALE)
      binding.bind(socket.fileDescriptor) // Must be unconnected when binding the Network.
      if (!current()) return failed(CoreFairwayFdFailureReason.STALE)
      if (!binding.protect(socket.fd)) return failed(CoreFairwayFdFailureReason.PROTECTION_FAILED)
      if (!current()) return failed(CoreFairwayFdFailureReason.STALE)
      val ip = destinationIpv4
      val address = InetAddress.getByAddress(byteArrayOf(
        (ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte(),
      ))
      // UDP connect sends no packet. No application source-port bind: the native
      // flow engine owns inner/outer tuple translation and packet construction.
      Os.connect(socket.fileDescriptor, address, destinationPort)
      if (!current()) return failed(CoreFairwayFdFailureReason.STALE)
      val code = deliverBorrowed(socket.fd)
      check(code <= 0) { "Invalid Fairway FD admission result" }
      return if (code == 0) null else CoreFairwayFdFailure(CoreFairwayFdFailureReason.NATIVE_REJECTED, code)
    } catch (_: IOException) {
      return failed(CoreFairwayFdFailureReason.IO_FAILURE)
    } catch (_: ErrnoException) {
      return failed(CoreFairwayFdFailureReason.IO_FAILURE)
    } finally {
      // Do not swallow a close failure and pretend ownership was released.
      owned?.close()
    }
  }
}
