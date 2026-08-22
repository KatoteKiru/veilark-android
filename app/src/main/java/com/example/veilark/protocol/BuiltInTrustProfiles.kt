package com.example.veilark.protocol

import android.content.Context
import com.example.veilark.BuildConfig
import com.example.veilark.profile.SecureProfileStore
import com.example.veilark.profile.SubscriptionOrigin
import java.security.MessageDigest

object BuiltInTrustProfiles {
  private const val ASSET_NAME = "builtin_trust_profiles.txt"
  private const val DIGEST_KEY = "builtin_trust_digest_v4"
  private const val COUNT_KEY = "builtin_trust_count_v4"

  fun install(context: Context): List<TrustTunnelCatalogEntry> {
    val existing = TrustTunnelCatalog.load(context)
    if (!BuildConfig.EMBEDDED_TRUST_PROFILES) return existing

    val retiredHost = BuildConfig.RETIRED_TRUST_HOST.takeIf(String::isNotBlank)
    val activeConfig = if (
      SecureProfileStore.exists(context, SecureProfileStore.TRUST_TUNNEL)
    ) {
      SecureProfileStore.load(context, SecureProfileStore.TRUST_TUNNEL)
    } else {
      null
    }
    val activeRetired = retiredHost != null &&
      activeConfig?.contains(retiredHost, ignoreCase = true) == true
    val preferences = context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE)
    val cleaned = if (retiredHost != null && existing.any {
        it.config.contains(retiredHost, ignoreCase = true)
      }) {
      TrustTunnelCatalog.removeWhere(context) {
        it.config.contains(retiredHost, ignoreCase = true)
      }
    } else {
      existing
    }
    val payload = context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use {
      it.readText()
    }
    val digest = digest(payload)
    val builtInSourceId = TrustTunnelCatalog.sourceId(null, BUILT_IN_SOURCE_IDENTITY)
    val installedBuiltIns = cleaned.filter { it.sourceId == builtInSourceId }
    val profiles = if (
      preferences.getString(DIGEST_KEY, null) == digest &&
      preferences.getInt(COUNT_KEY, -1) == installedBuiltIns.size &&
      installedBuiltIns.isNotEmpty()
    ) {
      cleaned
    } else {
      val compiled = decode(payload).map(TrustTunnelProfile::compile)
      TrustTunnelCatalog.replaceSource(
        context = context,
        profiles = compiled,
        sourceUrl = null,
        origin = SubscriptionOrigin.BUILT_IN,
        localIdentity = BUILT_IN_SOURCE_IDENTITY,
      ).also {
        preferences.edit()
          .putString(DIGEST_KEY, digest)
          .putInt(COUNT_KEY, it.count { profile -> profile.sourceId == builtInSourceId })
          .apply()
      }
    }
    val preferredId = preferences.getString("selected_trust_profile", null)
    val migratedSelection = profiles.firstOrNull { it.id == preferredId }
      ?: activeConfig?.let { config -> profiles.firstOrNull { it.config == config } }
    migratedSelection?.let { selected ->
      if (activeConfig != selected.config) {
        TrustTunnelCatalog.activate(context, profiles, selected.id)
      }
      if (preferredId != selected.id) {
        preferences.edit()
          .putString("selected_trust_profile", selected.id)
          .putString("trust_display_name", selected.name)
          .apply()
      }
    }
    if (activeRetired ||
      !SecureProfileStore.exists(context, SecureProfileStore.TRUST_TUNNEL)
    ) {
      TrustTunnelCatalog.activate(context, profiles, profiles.first().id)
    }
    if (!preferences.contains("profile_engine") &&
      !SecureProfileStore.exists(context, SecureProfileStore.SING_BOX)
    ) {
      val active = TrustTunnelCatalog.activate(context, profiles, profiles.first().id)
      preferences.edit()
        .putString("profile_engine", ProfileEngine.TRUST_TUNNEL)
        .putString("selected_trust_profile", active.id)
        .putString("trust_display_name", active.name)
        .apply()
    }
    return profiles
  }

  internal fun digest(payload: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(payload.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  internal fun decode(payload: String): List<String> {
    val links = payload.lineSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .toList()
    require(links.isNotEmpty()) { "Встроенные профили TrustTunnel отсутствуют" }
    require(links.all { it.startsWith("tt://") }) {
      "Встроенный профиль TrustTunnel повреждён"
    }
    return links.distinct()
  }

  private const val BUILT_IN_SOURCE_IDENTITY = "veilark-builtin-trust-v1"

}
