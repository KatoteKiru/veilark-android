package com.example.veilark.core.runtime

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import java.net.Inet4Address
import java.net.Inet6Address

/** Passive OS observations, never a reachability test or a source of connection state. */
internal class CoreNetworkDiagnostics(
  private val connectivity: ConnectivityManager,
  private val log: (String) -> Unit,
) : AutoCloseable {
  private val lock = Any()
  private var closed = false
  private val registered = mutableListOf<ConnectivityManager.NetworkCallback>()
  private val ownedVpn = mutableSetOf<Network>()
  private val last = mutableMapOf<Pair<String, Network>, String>()

  fun start() {
    // Core excludes its own UID from the tunnel. Its default is therefore NOT a
    // tunnel probe, even if that default happens to report VALIDATED.
    log("network_observation app_default_is_not_tunnel_probe=true vpn_visibility=" +
      if (Build.VERSION.SDK_INT >= 31) "other_uid_requested" else "uid_scoped_may_hide_vpn")
    register(callback(ownVpnOnly = false)) { connectivity.registerDefaultNetworkCallback(it) }
    if (Build.VERSION.SDK_INT >= 30) {
      val request = NetworkRequest.Builder()
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
      if (Build.VERSION.SDK_INT >= 31) request.setIncludeOtherUidNetworks(true)
      register(callback(ownVpnOnly = true)) { connectivity.registerNetworkCallback(request.build(), it) }
    } else {
      log("network_observation own_vpn_unavailable=owner_uid_api_requires_30")
    }
  }

  private fun register(callback: ConnectivityManager.NetworkCallback,
    action: (ConnectivityManager.NetworkCallback) -> Unit) {
    if (synchronized(lock) { closed }) return
    try {
      action(callback)
      val remove = synchronized(lock) {
        if (closed) true else { registered += callback; false }
      }
      if (remove) unregister(callback)
    } catch (failure: RuntimeException) {
      // Diagnostics must not fail a working VPN. Never log exception text.
      emit("network_observation registration_failed=${failure.javaClass.simpleName}")
    }
  }

  private fun callback(ownVpnOnly: Boolean) = object : ConnectivityManager.NetworkCallback() {
    private val scope = if (ownVpnOnly) "OWN_VPN" else "APP_DEFAULT"

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
      synchronized(lock) {
        if (closed) return
        if (ownVpnOnly) {
          if (Build.VERSION.SDK_INT < 30 || caps.ownerUid != Process.myUid() ||
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
          ownedVpn += network
        }
        val value = "event=capabilities vpn=${caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} " +
          "internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
          "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
          "captive=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)} " +
          "suspended=${!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)}"
        changed(scope, network, value)
      }
    }

    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
      synchronized(lock) {
        if (closed || (ownVpnOnly && network !in ownedVpn)) return
        // Deliberately omit IPs, interface names, DNS hostnames and search domains.
        val value = "event=link mtu=${properties.mtu} dns_count=${properties.dnsServers.size} " +
          "private_dns_active=${properties.isPrivateDnsActive} " +
          "private_dns_strict=${properties.privateDnsServerName != null} " +
          "ipv4_count=${properties.linkAddresses.count { it.address is Inet4Address }} " +
          "ipv6_count=${properties.linkAddresses.count { it.address is Inet6Address }}"
        changed("${scope}_LINK", network, value)
      }
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
      synchronized(lock) {
        if (closed || (ownVpnOnly && network !in ownedVpn)) return
        changed("${scope}_BLOCKED", network, "event=blocked blocked=$blocked")
      }
    }

    override fun onLost(network: Network) {
      synchronized(lock) {
        if (closed || (ownVpnOnly && !ownedVpn.remove(network))) return
        last.keys.removeAll { it.second == network && it.first.startsWith(scope) }
        log("network_scope=$scope event=lost")
      }
    }
  }

  private fun changed(scope: String, network: Network, value: String) {
    val key = scope to network
    if (last.put(key, value) != value) log("network_scope=$scope $value")
  }

  private fun emit(value: String) = synchronized(lock) { if (!closed) log(value) }

  override fun close() {
    val callbacks = synchronized(lock) {
      if (closed) return
      closed = true
      registered.toList().also { registered.clear(); ownedVpn.clear(); last.clear() }
    }
    callbacks.forEach(::unregister)
  }

  private fun unregister(callback: ConnectivityManager.NetworkCallback) {
    try { connectivity.unregisterNetworkCallback(callback) }
    catch (_: RuntimeException) { /* Already gone on OS/service teardown. */ }
  }
}
