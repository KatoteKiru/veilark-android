package com.example.veilark.profile

import android.content.Context
import org.json.JSONObject

object NetworkProfileMigration {
  private const val RECOVERY_KEY = "stable_network_recovery_079"

  fun migrateStored(context: Context) {
    if (!SecureProfileStore.exists(context, SecureProfileStore.SING_BOX)) return
    val current = SecureProfileStore.load(context, SecureProfileStore.SING_BOX)
    val migrated = migrate(current)
    if (migrated != current) {
      SecureProfileStore.save(context, SecureProfileStore.SING_BOX, migrated)
    }
  }

  fun reconcileStoredSettings(context: Context) {
    if (!SecureProfileStore.exists(context, SecureProfileStore.SING_BOX)) return
    val preferences = context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE)
    var routingMode = preferences.getString(
      "routing_mode",
      ProfileSelection.ROUTING_ALL,
    ) ?: ProfileSelection.ROUTING_ALL
    var applicationMode = preferences.getString(
      "application_mode",
      ProfileSelection.APPS_ALL,
    ) ?: ProfileSelection.APPS_ALL
    var dpiMode = preferences.getString(
      "dpi_mode",
      ProfileSelection.DPI_OFF,
    ) ?: ProfileSelection.DPI_OFF
    val directRoutes = preferences.getString("direct_routes", "").orEmpty()
    val vpnRoutes = preferences.getString("vpn_routes", "").orEmpty()
    val selectedApplications = preferences
      .getStringSet("selected_applications", emptySet())
      ?.toSet()
      .orEmpty()

    if (
      routingMode !in setOf(ProfileSelection.ROUTING_ALL, ProfileSelection.ROUTING_MANUAL) ||
      (routingMode == ProfileSelection.ROUTING_MANUAL &&
        directRoutes.isBlank() && vpnRoutes.isBlank())
    ) {
      routingMode = ProfileSelection.ROUTING_ALL
    }
    if (
      applicationMode !in setOf(
        ProfileSelection.APPS_ALL,
        ProfileSelection.APPS_ONLY,
        ProfileSelection.APPS_BYPASS,
      ) ||
      (applicationMode != ProfileSelection.APPS_ALL && selectedApplications.isEmpty())
    ) {
      applicationMode = ProfileSelection.APPS_ALL
    }
    if (dpiMode !in setOf(ProfileSelection.DPI_OFF, ProfileSelection.DPI_TLS_FRAGMENT)) {
      dpiMode = ProfileSelection.DPI_OFF
    }

    val current = SecureProfileStore.load(context, SecureProfileStore.SING_BOX)
    val reconciled = reconcile(
      config = current,
      routingMode = routingMode,
      directRoutes = directRoutes,
      vpnRoutes = vpnRoutes,
      applicationMode = applicationMode,
      selectedApplications = selectedApplications,
      vpnPackage = context.packageName,
      dpiMode = dpiMode,
    )
    if (reconciled != current) {
      SecureProfileStore.save(context, SecureProfileStore.SING_BOX, reconciled)
    }
    preferences.edit()
      .putString("routing_mode", routingMode)
      .putString("application_mode", applicationMode)
      .putString("dpi_mode", dpiMode)
      .apply()
  }

  internal fun reconcile(
    config: String,
    routingMode: String,
    directRoutes: String,
    vpnRoutes: String,
    applicationMode: String,
    selectedApplications: Set<String>,
    vpnPackage: String,
    dpiMode: String,
  ): String {
    var reconciled = ProfileSelection.applyRouting(
      config,
      routingMode,
      directRoutes,
      vpnRoutes,
    )
    reconciled = ProfileSelection.applyApplications(
      reconciled,
      applicationMode,
      selectedApplications,
      vpnPackage,
    )
    return ProfileSelection.applyDpiProtection(reconciled, dpiMode)
  }

  fun recoverStableDefaults(context: Context) {
    val preferences = context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE)
    if (preferences.getBoolean(RECOVERY_KEY, false)) return
    if (SecureProfileStore.exists(context, SecureProfileStore.SING_BOX)) {
      val current = SecureProfileStore.load(context, SecureProfileStore.SING_BOX)
      SecureProfileStore.save(context, SecureProfileStore.SING_BOX, recoverStableDefaults(current))
    }
    preferences.edit()
      .putString("routing_mode", ProfileSelection.ROUTING_ALL)
      .putString("application_mode", ProfileSelection.APPS_ALL)
      .putString("dpi_mode", ProfileSelection.DPI_OFF)
      .putString("direct_routes", "")
      .putString("vpn_routes", "")
      .remove("selected_applications")
      .putBoolean(RECOVERY_KEY, true)
      .apply()
  }

  internal fun migrate(config: String): String {
    val root = JSONObject(config)
    root.optJSONObject("dns")?.let { dns ->
      dns.put("reverse_mapping", true)
      dns.put("strategy", "ipv4_only")
      dns.optJSONArray("servers")?.let { servers ->
        repeat(servers.length()) { index ->
          val server = servers.optJSONObject(index) ?: return@repeat
          if (server.optString("tag") == "secure-dns" &&
            server.optString("type") == "https"
          ) {
            server.put(
              "tls",
              JSONObject()
                .put("enabled", true)
                .put("server_name", "cloudflare-dns.com"),
            )
          }
        }
      }
    }
    root.optJSONArray("inbounds")?.let { inbounds ->
      repeat(inbounds.length()) { index ->
        val inbound = inbounds.optJSONObject(index) ?: return@repeat
        if (inbound.optString("type") == "tun") inbound.put("mtu", 1280)
      }
    }
    root.optJSONArray("outbounds")?.let { outbounds ->
      repeat(outbounds.length()) { index ->
        val outbound = outbounds.optJSONObject(index) ?: return@repeat
        if (outbound.optString("type") == "urltest" &&
          outbound.optString("tag") == ProfileSelection.AUTOMATIC_TAG
        ) {
          outbound.put("url", "https://cp.cloudflare.com/generate_204")
        }
      }
    }
    return root.toString(2)
  }

  internal fun recoverStableDefaults(config: String): String {
    val root = JSONObject(migrate(config))
    root.optJSONObject("dns")?.put("strategy", "ipv4_only")
    root.optJSONArray("inbounds")?.let { inbounds ->
      repeat(inbounds.length()) { index ->
        val inbound = inbounds.optJSONObject(index) ?: return@repeat
        if (inbound.optString("type") == "tun") {
          inbound.remove("include_package")
          inbound.remove("exclude_package")
          inbound.put("mtu", 1280)
        }
      }
    }
    root.optJSONObject("route")?.remove("rules")
    root.optJSONArray("outbounds")?.let { outbounds ->
      repeat(outbounds.length()) { index ->
        val outbound = outbounds.optJSONObject(index) ?: return@repeat
        if (outbound.optString("type") == "urltest" &&
          outbound.optString("tag") == ProfileSelection.AUTOMATIC_TAG
        ) {
          outbound.put("interval", "5m")
          outbound.put("idle_timeout", "10m")
        }
        outbound.optJSONObject("tls")?.apply {
          remove("fragment")
          remove("fragment_fallback_delay")
          remove("record_fragment")
        }
      }
    }
    return root.toString(2)
  }
}
