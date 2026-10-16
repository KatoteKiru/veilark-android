package com.example.veilark.core.runtime

import com.example.veilark.profile.ApplicationRoutingPolicy
import com.example.veilark.profile.CoreProfileKind
import com.example.veilark.profile.ProfileSelection

/** Side-effect-free policy admission; callers run this before stopping another VPN. */
internal object CoreConnectionPreflight {
  fun prepare(
    kind: CoreProfileKind,
    configuredDns: List<String>,
    snapshot: Map<String, *>,
    api: Int,
    vpnPackage: String,
    runtimeAvailable: (CoreProfileKind) -> Boolean,
    installed: (String) -> Boolean,
    readAsset: (String) -> ByteArray,
  ): CoreRoutingPlan {
    check(runtimeAvailable(kind)) { "Selected Veilark Core runtime is unavailable" }
    fun text(key: String, default: String): String {
      val value = snapshot[key] ?: return default
      require(value is String) { "Invalid saved Core policy" }
      return value
    }
    val routes = CoreRoutingPlan.prepare(
      text("core_routing_mode", ProfileSelection.ROUTING_ALL),
      text("core_direct_routes", ""), text("core_vpn_routes", ""),
      CoreL3DnsPolicy.effective(configuredDns).resolvers, api, readAsset,
    )
    val packages = snapshot["selected_applications"]
    require(packages == null || packages is Set<*> && packages.all { it is String }) {
      "Invalid saved Core application selection"
    }
    ApplicationRoutingPolicy.fromPreferences(
      text("application_mode", ProfileSelection.APPS_ALL),
      (packages as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty(),
    ).forAndroidVpn(vpnPackage).applyInstalled(vpnPackage, installed, installed)
    return routes
  }
}
