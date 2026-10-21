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
 * API29/30 use a bounded passive physical inventory plus independently verified
 * default hints. This is conditional selection, not platform best-score parity;
 * the production API31 preflight remains unchanged pending acceptance.
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
  private val legacyMode = Build.VERSION.SDK_INT < 31
  private val epochs = CoreFairwayNetworkEpoch<Network>()
  private val legacy = CoreFairwayLegacyNetworkSelector<Network>()
  private val defaultHint = CoreFairwayDefaultNetworkHint<Network>()
  private val current = MutableStateFlow<CoreFairwayCapturedNetwork?>(null)
  val captures: StateFlow<CoreFairwayCapturedNetwork?> = current.asStateFlow()
  private var closed = false
  private data class Selection(val network: Network, val epoch: Long, val usable: () -> Boolean)
  private var selection: Selection? = null
  private val handler by lazy { Handler(Looper.getMainLooper()) }
  private val physicalRequest by lazy {
    NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
      .build()
  }
  @Volatile var lastChange: CoreFairwayNetworkChange = CoreFairwayNetworkChange.NONE
    private set

  private data class LinkSignature(
    val name: String?, val mtu: Int, val addresses: List<LinkAddress>,
    val routes: List<RouteInfo>, val dns: List<InetAddress>,
    val privateDns: Boolean, val privateDnsName: String?,
  )

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = updatePhysical(CoreFairwayNetworkChange.AVAILABLE,
      { available(network) }, { available(network) })
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
      val eligible = eligible(caps)
      updatePhysical(CoreFairwayNetworkChange.CAPABILITIES,
        { capabilities(network, eligible) }, { capabilities(network, eligible) }, if (eligible) null else network)
    }
    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = updatePhysical(
      CoreFairwayNetworkChange.BLOCKED, { this.blocked(network, blocked) }, { this.blocked(network, blocked) },
      if (blocked) network else null)
    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
      val signature = LinkSignature(properties.interfaceName, properties.mtu,
        properties.linkAddresses.toList(), properties.routes.toList(), properties.dnsServers.toList(),
        properties.isPrivateDnsActive, properties.privateDnsServerName)
      updatePhysical(CoreFairwayNetworkChange.LINK,
        { link(network, signature) }, { link(network, signature) })
    }
    override fun onLost(network: Network) = updatePhysical(CoreFairwayNetworkChange.LOST,
      { lost(network) }, { lost(network) }, network)
  }

  private val hintCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = updateHint(CoreFairwayNetworkChange.AVAILABLE) {
      available(network) // Provisional: never certifies a physical network.
    }
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateHint(
      CoreFairwayNetworkChange.CAPABILITIES) { capabilities(network, eligible(caps)) }
    override fun onLost(network: Network) = updateHint(CoreFairwayNetworkChange.LOST) { lost(network) }
  }

  private val registrations = CoreFairwayCallbackCustody<ConnectivityManager.NetworkCallback>(
    invalidate = { invalidate() },
    register = { observer ->
      if (observer === callback) {
        if (Build.VERSION.SDK_INT >= 31) connectivity.registerBestMatchingNetworkCallback(physicalRequest, observer, handler)
        else connectivity.registerNetworkCallback(physicalRequest, observer, handler)
      } else {
        check(legacyMode && observer === hintCallback)
        connectivity.registerDefaultNetworkCallback(observer, handler)
      }
    },
    unregister = connectivity::unregisterNetworkCallback,
  )

  private fun eligible(caps: NetworkCapabilities) =
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) &&
      !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

  /** Register once; partial failures close leases and roll back both observers. */
  fun start() {
    registrations.start(if (legacyMode) listOf(callback, hintCallback) else listOf(callback))
  }

  fun isCurrent(epoch: Long): Boolean = synchronized(lock) {
    !closed && current.value?.epoch == epoch
  }

  /** Declare the same captured upstream used by outer and direct sockets.
   * Raw Network custody stays here; a stale lease cannot configure a new TUN.
   */
  fun applyUnderlying(capture: CoreFairwayCapturedNetwork, builder: VpnService.Builder) {
    synchronized(lock) {
      val selected = checkNotNull(selection) { "Stale Fairway TUN network" }
      check(!closed && current.value === capture && selected.epoch == capture.epoch &&
        selected.usable() && capture.binding.isUsable()) { "Stale Fairway TUN network" }
      builder.setUnderlyingNetworks(arrayOf(selected.network))
    }
  }

  private fun updatePhysical(
    reason: CoreFairwayNetworkChange,
    commonChange: CoreFairwayNetworkEpoch<Network>.() -> Unit,
    legacyChange: CoreFairwayLegacyNetworkSelector<Network>.() -> Unit,
    revokeHint: Network? = null,
  ) {
    synchronized(lock) {
      if (closed) return
      if (legacyMode) {
        if (revokeHint != null) defaultHint.invalidatePhysical(revokeHint)
        legacy.legacyChange()
      } else epochs.commonChange()
      publish(reason)
    }
  }

  private fun updateHint(reason: CoreFairwayNetworkChange, change: CoreFairwayDefaultNetworkHint<Network>.() -> Unit) {
    synchronized(lock) {
      if (closed || !legacyMode) return
      defaultHint.change()
      legacy.verifiedPhysicalDefault(defaultHint.snapshot())
      publish(reason)
    }
  }

  /** Called only under lock; raw Network and snapshot currency remain owner-local. */
  private fun publish(reason: CoreFairwayNetworkChange) {
    val selected = if (legacyMode) {
      legacy.snapshot()?.let { snapshot -> Selection(snapshot.network, snapshot.epoch) { legacy.isCurrent(snapshot) } }
    } else {
      epochs.snapshot()?.let { snapshot -> Selection(snapshot.network, snapshot.epoch) { epochs.isCurrent(snapshot) } }
    }
    if (current.value?.epoch != selected?.epoch) lastChange = reason
    if (selected == null) { selection = null; current.value = null; return }
    if (current.value?.epoch == selected.epoch) return
    selection = selected
    val binding = object : CoreFairwayNetworkBinding {
      override fun isUsable(): Boolean = selected.usable()
      override fun bind(fd: FileDescriptor) {
        if (!isUsable()) throw IOException("Stale Fairway network binding")
        selected.network.bindSocket(fd)
        if (!isUsable()) throw IOException("Fairway network changed during bind")
      }
      override fun protect(fd: Int): Boolean = isUsable() && protectSocket(fd) && isUsable()
    }
    current.value = CoreFairwayCapturedNetwork(selected.epoch, binding)
  }

  private fun invalidate() {
    synchronized(lock) {
      closed = true
      epochs.close()
      legacy.close()
      defaultHint.close()
      selection = null
      current.value = null
    }
  }

  override fun close() = registrations.close()
}
