package com.example.veilark.core.runtime

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable lease on one underlying Network, never the lifecycle engine epoch. */
internal class CoreFairwayCapturedNetwork internal constructor(
  val epoch: Long,
  val binding: CoreFairwayNetworkBinding,
)

/**
 * Dormant session-scoped observer for the app's default non-VPN network.
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

  private data class LinkSignature(
    val name: String?, val mtu: Int, val addresses: List<LinkAddress>,
    val routes: List<RouteInfo>, val dns: List<InetAddress>,
    val privateDns: Boolean, val privateDnsName: String?,
  )

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = update { available(network) }
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update {
      capabilities(network,
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) &&
          !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }
    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = update {
      this.blocked(network, blocked)
    }
    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update {
      link(network, LinkSignature(properties.interfaceName, properties.mtu,
        properties.linkAddresses.toList(), properties.routes.toList(), properties.dnsServers.toList(),
        properties.isPrivateDnsActive, properties.privateDnsServerName))
    }
    override fun onLost(network: Network) = update { lost(network) }
  }

  /** Register once. A registration error is fatal to this owner, never ignored. */
  fun start() {
    synchronized(lock) {
      if (closed || started) return
      started = true
    }
    try {
      connectivity.registerDefaultNetworkCallback(callback)
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

  private fun update(change: CoreFairwayNetworkEpoch<Network>.() -> Unit) {
    synchronized(lock) {
      if (closed) return
      epochs.change()
      val snapshot = epochs.snapshot()
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
