package com.example.veilark.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDeletionPolicyTest {
  @Test(expected = IllegalArgumentException::class)
  fun builtInSourceIsProtected() {
    SubscriptionDeletionPolicy.requireDeletable(SubscriptionOrigin.BUILT_IN)
  }

  @Test
  fun manualAndRemoteSourcesAreDeletable() {
    SubscriptionDeletionPolicy.requireDeletable(SubscriptionOrigin.MANUAL)
    SubscriptionDeletionPolicy.requireDeletable(SubscriptionOrigin.REMOTE)
  }

  @Test
  fun onlyDeletingSelectedSourceRequiresActiveTeardown() {
    assertTrue(SubscriptionDeletionPolicy.removesActiveSource("active", "active"))
    assertFalse(SubscriptionDeletionPolicy.removesActiveSource("active", "other"))
    assertFalse(SubscriptionDeletionPolicy.removesActiveSource(null, "other"))
  }

  @Test
  fun missingSavedSelectionFallsBackToFirstPersistedSource() {
    assertEquals(
      "first",
      SubscriptionSelectionPolicy.resolve("deleted", listOf("first", "second")),
    )
    assertEquals(
      "second",
      SubscriptionSelectionPolicy.resolve("second", listOf("first", "second")),
    )
    assertEquals(null, SubscriptionSelectionPolicy.resolve("deleted", emptyList()))
  }

  @Test
  fun refreshCommitRequiresTheOriginalSourceToStillExist() {
    assertTrue(SubscriptionRefreshPolicy.canCommit("source-a", listOf("source-a", "source-b")))
    assertFalse(SubscriptionRefreshPolicy.canCommit("source-a", listOf("source-b")))
    assertFalse(SubscriptionRefreshPolicy.canCommit("", listOf("source-a")))
  }
}
