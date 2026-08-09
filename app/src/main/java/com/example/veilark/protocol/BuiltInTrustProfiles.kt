package com.example.veilark.protocol

import android.content.Context
import com.example.veilark.profile.SecureProfileStore
import java.security.MessageDigest

object BuiltInTrustProfiles {
  private const val ASSET_NAME = "builtin_trust_profiles.txt"
  private const val RETIRED_NETHERLANDS_HOST = "edge.senyasenyavski.uk"
  private const val DIGEST_KEY = "builtin_trust_digest_v4"
  private const val COUNT_KEY = "builtin_trust_count_v4"

  fun install(context: Context): List<TrustTunnelCatalogEntry> {
    val activeRetired = SecureProfileStore.exists(context, SecureProfileStore.TRUST_TUNNEL) &&
      SecureProfileStore.load(context, SecureProfileStore.TRUST_TUNNEL)
        .contains(RETIRED_NETHERLANDS_HOST, ignoreCase = true)
    val preferences = context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE)
    val existing = TrustTunnelCatalog.load(context)
    val cleaned = if (existing.any {
        it.config.contains(RETIRED_NETHERLANDS_HOST, ignoreCase = true)
      }) {
      TrustTunnelCatalog.removeWhere(context) {
        it.config.contains(RETIRED_NETHERLANDS_HOST, ignoreCase = true)
      }
    } else {
      existing
    }
    val payload = context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use {
      it.readText()
    }
    val digest = digest(payload)
    val profiles = if (
      preferences.getString(DIGEST_KEY, null) == digest &&
      preferences.getInt(COUNT_KEY, -1) == cleaned.size &&
      cleaned.isNotEmpty()
    ) {
      cleaned
    } else {
      val compiled = decode(payload).map(TrustTunnelProfile::compile)
      TrustTunnelCatalog.upsert(context, compiled).also {
        preferences.edit()
          .putString(DIGEST_KEY, digest)
          .putInt(COUNT_KEY, it.size)
          .apply()
      }
    }
    preferences.getString("selected_trust_profile", null)
      ?.let { selectedId ->
        profiles.firstOrNull { it.id == selectedId }
          ?.let { selected ->
            if (!SecureProfileStore.exists(context, SecureProfileStore.TRUST_TUNNEL) ||
              SecureProfileStore.load(context, SecureProfileStore.TRUST_TUNNEL) != selected.config
            ) {
              TrustTunnelCatalog.activate(context, profiles, selected.id)
            }
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

}
