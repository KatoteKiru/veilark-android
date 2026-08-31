package com.example.veilark.protocol

import com.example.veilark.profile.SubscriptionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInTrustProfileMigrationTest {
  @Test
  fun removesOnlyBuiltInEntries() {
    val entries = listOf(
      entry("built-in", "config-built-in", SubscriptionOrigin.BUILT_IN),
      entry("remote", "config-remote", SubscriptionOrigin.REMOTE),
      entry("manual", "config-manual", SubscriptionOrigin.MANUAL),
      entry("legacy", "config-legacy", SubscriptionOrigin.LEGACY),
    )

    val plan = BuiltInTrustProfileMigration.planRemoval(entries, null, null)

    assertEquals(setOf("built-in"), plan.removedIds)
    assertEquals(listOf("remote", "manual", "legacy"), plan.retained.map { it.id })
    assertFalse(plan.deleteActiveSecureProfile)
    assertFalse(plan.clearSelectedProfile)
  }

  @Test
  fun deletesActiveSecureProfileOnlyWhenItMatchesRemovedBuiltIn() {
    val entries = listOf(
      entry("built-in", "config-built-in", SubscriptionOrigin.BUILT_IN),
      entry("manual", "config-manual", SubscriptionOrigin.MANUAL),
    )

    assertTrue(
      BuiltInTrustProfileMigration.planRemoval(
        entries,
        activeConfig = "config-built-in",
        selectedProfileId = null,
      ).deleteActiveSecureProfile,
    )
    assertFalse(
      BuiltInTrustProfileMigration.planRemoval(
        entries,
        activeConfig = "config-manual",
        selectedProfileId = null,
      ).deleteActiveSecureProfile,
    )
    assertFalse(
      BuiltInTrustProfileMigration.planRemoval(
        entries,
        activeConfig = "unrelated-active-config",
        selectedProfileId = null,
      ).deleteActiveSecureProfile,
    )
  }

  @Test
  fun preservesActiveProfileWhenRetainedEntryHasSameConfig() {
    val entries = listOf(
      entry("built-in", "shared-config", SubscriptionOrigin.BUILT_IN),
      entry("remote", "shared-config", SubscriptionOrigin.REMOTE),
    )

    val plan = BuiltInTrustProfileMigration.planRemoval(
      entries,
      activeConfig = "shared-config",
      selectedProfileId = "built-in",
    )

    assertFalse(plan.deleteActiveSecureProfile)
    assertTrue(plan.clearSelectedProfile)
    assertEquals(listOf("remote"), plan.retained.map { it.id })
  }

  @Test
  fun clearsSelectionOnlyWhenSelectedEntryIsRemoved() {
    val entries = listOf(
      entry("built-in", "config-built-in", SubscriptionOrigin.BUILT_IN),
      entry("remote", "config-remote", SubscriptionOrigin.REMOTE),
    )

    assertTrue(
      BuiltInTrustProfileMigration.planRemoval(
        entries,
        activeConfig = null,
        selectedProfileId = "built-in",
      ).clearSelectedProfile,
    )
    assertFalse(
      BuiltInTrustProfileMigration.planRemoval(
        entries,
        activeConfig = null,
        selectedProfileId = "remote",
      ).clearSelectedProfile,
    )
  }

  @Test
  fun failsClosedWhenActiveBuiltInCannotBeDeleted() {
    var failure: IllegalStateException? = null
    try {
      BuiltInTrustProfileMigration.requireActiveProfileRemoved(
        deleted = false,
        stillExists = true,
      )
    } catch (expected: IllegalStateException) {
      failure = expected
    }

    assertTrue(failure != null)
  }

  private fun entry(
    id: String,
    config: String,
    origin: SubscriptionOrigin,
  ): TrustTunnelCatalogEntry = TrustTunnelCatalogEntry(
    id = id,
    name = id,
    config = config,
    sourceId = "source-$id",
    origin = origin,
    fingerprint = "fingerprint-$id",
  )
}
