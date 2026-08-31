package com.example.veilark.protocol

import android.content.Context
import com.example.veilark.profile.SecureProfileStore
import com.example.veilark.profile.SubscriptionOrigin

internal data class BuiltInTrustRemovalPlan(
  val retained: List<TrustTunnelCatalogEntry>,
  val removedIds: Set<String>,
  val deleteActiveSecureProfile: Boolean,
  val clearSelectedProfile: Boolean,
)

object BuiltInTrustProfileMigration {
  fun migrate(context: Context): List<TrustTunnelCatalogEntry> {
    val entries = TrustTunnelCatalog.load(context)
    val preferences = context.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)
    val activeConfig = if (
      SecureProfileStore.exists(context, SecureProfileStore.TRUST_TUNNEL)
    ) {
      SecureProfileStore.load(context, SecureProfileStore.TRUST_TUNNEL)
    } else {
      null
    }
    val plan = planRemoval(
      entries = entries,
      activeConfig = activeConfig,
      selectedProfileId = preferences.getString(SELECTED_PROFILE_KEY, null),
    )

    if (plan.deleteActiveSecureProfile) {
      val deleted = SecureProfileStore.delete(context, SecureProfileStore.TRUST_TUNNEL)
      requireActiveProfileRemoved(
        deleted = deleted,
        stillExists = SecureProfileStore.exists(context, SecureProfileStore.TRUST_TUNNEL),
      )
    }

    val retained = if (plan.removedIds.isEmpty()) {
      entries
    } else {
      TrustTunnelCatalog.removeWhere(context) { entry ->
        entry.origin == SubscriptionOrigin.BUILT_IN
      }
    }

    preferences.edit()
      .remove(LEGACY_DIGEST_KEY)
      .remove(LEGACY_COUNT_KEY)
      .apply {
        if (plan.clearSelectedProfile) {
          remove(SELECTED_PROFILE_KEY)
          remove(TRUST_DISPLAY_NAME_KEY)
        }
      }
      .apply()
    return retained
  }

  internal fun planRemoval(
    entries: List<TrustTunnelCatalogEntry>,
    activeConfig: String?,
    selectedProfileId: String?,
  ): BuiltInTrustRemovalPlan {
    val removed = entries.filter { it.origin == SubscriptionOrigin.BUILT_IN }
    val retained = entries.filterNot { it.origin == SubscriptionOrigin.BUILT_IN }
    val activeMatchesRemoved = activeConfig != null && removed.any { it.config == activeConfig }
    val activeMatchesRetained = activeConfig != null && retained.any { it.config == activeConfig }
    return BuiltInTrustRemovalPlan(
      retained = retained,
      removedIds = removed.mapTo(linkedSetOf()) { it.id },
      deleteActiveSecureProfile = activeMatchesRemoved && !activeMatchesRetained,
      clearSelectedProfile = selectedProfileId != null && removed.any {
        it.id == selectedProfileId
      },
    )
  }

  internal fun requireActiveProfileRemoved(deleted: Boolean, stillExists: Boolean) {
    check(deleted || !stillExists) {
      "Built-in TrustTunnel profile could not be removed"
    }
  }

  private const val PROFILE_PREFERENCES = "profile_meta"
  private const val SELECTED_PROFILE_KEY = "selected_trust_profile"
  private const val TRUST_DISPLAY_NAME_KEY = "trust_display_name"
  private const val LEGACY_DIGEST_KEY = "builtin_trust_digest_v4"
  private const val LEGACY_COUNT_KEY = "builtin_trust_count_v4"
}
