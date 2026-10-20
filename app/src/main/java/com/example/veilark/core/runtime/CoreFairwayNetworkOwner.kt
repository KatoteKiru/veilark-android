package com.example.veilark.core.runtime

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.RouteInfo
import android.net.VpnService
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class CoreFairwayNetworkChange { NONE, AVAILABLE, CAPABILITIES, BLOCKED, LINK, LOST }

/** Immutable lease on one underlying Network, never the lifecycle engine epoch. */
internal class CoreFairwayCapturedNetwork internal constructor(
  val epoch: Long,
  val binding: CoreFairwayNetworkBinding,
)

/**
 * Session-scoped observer for the best physical network on API31+;
 * API29/30 default observer is retained only for dormant helper compatibility.
 * Core's own UID must remain outside its TUN (the existing tunBuilder policy).
 * No requestNetwork, probes, timers, process binding, JNI or service activation.
 * Callback snapshots avoid racing synchronous ConnectivityManager lookups.
 *
 * A future host must coordinate these changes with native epoch invalidation,
 * cancel/join the old broker, and retain native full-token/FD-registration fencing.
 * Java currency alone is not an atomic native admission acknowledgement.
 */
internal class CoreFairwayNetworkOwner(
  private val connectivity: ConnectivityManager,
  private val protectSocket: (Int) -> Boolean,
) : AutoCloseable {
  private val lock = Any()
  private val epochs = CoreFairwayNetworkEpoch<Network>()
  private val current = MutableStateFlow<CoreFairwayCapturedNetwork?>(null)
  val captures: StateFlow<CoreFairwayCapturedNetwork?> = current.asStateFlow()
  private var started = false
  private var registered = false
  private var closed = false
  @Volatile var lastChange: CoreFairwayNetworkChange = CoreFairwayNetworkChange.NONE
    private set

  private data class LinkSignature(
    val name: String?, val mtu: Int, val addresses: List<LinkAddress>,
    val routes: List<RouteInfo>, val dns: List<InetAddress>,
    val privateDns: Boolean, val privateDnsName: String?,
  )

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = update(CoreFairwayNetworkChange.AVAILABLE) { available(network) }
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(CoreFairwayNetworkChange.CAPABILITIES) {
      capabilities(network,
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) &&
          !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }
    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = update(CoreFairwayNetworkChange.BLOCKED) {
      this.blocked(network, blocked)
    }
    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update(CoreFairwayNetworkChange.LINK) {
      link(network, LinkSignature(properties.interfaceName, properties.mtu,
        properties.linkAddresses.toList(), properties.routes.toList(), properties.dnsServers.toList(),
        properties.isPrivateDnsActive, properties.privateDnsServerName))
    }
    override fun onLost(network: Network) = update(CoreFairwayNetworkChange.LOST) { lost(network) }
  }

  /** Register once. A registration error is fatal to this owner, never ignored. */
  fun start() {
    synchronized(lock) {
      if (closed || started) return
      started = true
    }
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        // An app-default observer may report a newly created VPN. Physical
        // upstream selection must exclude VPNs in the request, before onAvailable.
        // This passive callback never requests/keeps an extra radio network alive.
        val request = NetworkRequest.Builder()
          .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
          .build()
        connectivity.registerBestMatchingNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
      } else {
        // Full common routing is preflight-gated to API31+ until a separately
        // accepted passive API29/30 physical-network selector is implemented.
        connectivity.registerDefaultNetworkCallback(callback)
      }
    } catch (error: RuntimeException) {
      synchronized(lock) { closed = true; epochs.close(); current.value = null }
      throw error
    }
    val remove = synchronized(lock) {
      registered = true
      closed
    }
    // close() may have won while the OS was registering the callback.
    if (remove) unregister()
  }

  fun isCurrent(epoch: Long): Boolean = synchronized(lock) {
    !closed && current.value?.epoch == epoch
  }

  /** Declare the same captured upstream used by outer and direct sockets.
   * Raw Network custody stays here; a stale lease cannot configure a new TUN.
   */
  fun applyUnderlying(capture: CoreFairwayCapturedNetwork, builder: VpnService.Builder) {
    synchronized(lock) {
      val snapshot = epochs.snapshot()
      check(!closed && current.value === capture && snapshot?.epoch == capture.epoch &&
        capture.binding.isUsable()) { "Stale Fairway TUN network" }
      builder.setUnderlyingNetworks(arrayOf(checkNotNull(snapshot).network))
    }
  }

  private fun update(reason: CoreFairwayNetworkChange, change: CoreFairwayNetworkEpoch<Network>.() -> Unit) {
    synchronized(lock) {
      if (closed) return
      epochs.change()
      val snapshot = epochs.snapshot()
      if (current.value?.epoch != snapshot?.epoch) lastChange = reason
      if (snapshot == null) { current.value = null; return }
      if (current.value?.epoch == snapshot.epoch) return
      val binding = object : CoreFairwayNetworkBinding {
        override fun isUsable(): Boolean = epochs.isCurrent(snapshot)
        override fun bind(fd: FileDescriptor) {
          if (!isUsable()) throw IOException("Stale Fairway network binding")
          snapshot.network.bindSocket(fd)
          if (!isUsable()) throw IOException("Fairway network changed during bind")
        }
        override fun protect(fd: Int): Boolean = isUsable() && protectSocket(fd) && isUsable()
      }
      current.value = CoreFairwayCapturedNetwork(snapshot.epoch, binding)
    }
  }

  override fun close() {
    synchronized(lock) {
      closed = true
      epochs.close() // Invalidate borrowed bindings before unregistering callbacks.
      current.value = null
    }
    unregister()
  }

  private fun unregister() = synchronized(lock) {
    if (registered) {
      // Keep registration custody if the OS throws; a later close can retry.
      connectivity.unregisterNetworkCallback(callback)
      registered = false
    }
  }
}
