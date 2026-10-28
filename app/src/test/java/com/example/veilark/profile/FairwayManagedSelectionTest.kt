package com.example.veilark.profile

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FairwayManagedSelectionTest {
  private val managedId = "veilark-managed-fairway-" + "a".repeat(64)
  @Test fun explicitManagedSelectionDoesNotChangeLegacyOrSignedEnrollmentSelection() {
    assertNull(CoreProfileStore.managedSelection(emptyMap<String, Any>()))
    assertNull(CoreProfileStore.managedSelection(mapOf("core_profile_kind" to "argon-v1")))
    val selected = mapOf("core_profile_kind" to CoreProfileKind.ARGON_V1.preferenceValue,
      "core_managed_fairway_selected" to managedId)
    assertEquals(managedId, CoreProfileStore.managedSelection(selected))
  }

  @Test fun malformedAndAmbiguousSelectionCannotFallBackToStaticIdentity() {
    val selected = mapOf<String, Any>("core_profile_kind" to CoreProfileKind.ARGON_V1.preferenceValue,
      "core_managed_fairway_selected" to managedId)
    for (invalid in listOf(selected + ("core_managed_fairway_selected" to false),
      selected + ("core_managed_fairway_selected" to "true"), selected - "core_profile_kind",
      selected + ("core_managed_fairway_selected" to managedId.uppercase()),
      selected + ("core_managed_fairway_selected" to (managedId + "/../static")),
      selected + ("core_profile_kind" to CoreProfileKind.LEGACY_V1.preferenceValue),
      selected + ("core_enrollment_issuer" to "issuer"), selected + ("core_enrollment_profile" to "profile"))) {
      assertThrows(IllegalStateException::class.java) { CoreProfileStore.managedSelection(invalid) }
    }
  }

  @Test fun managedAndStaticProfilesHaveDistinctEncryptedRecordPaths() {
    val root = File("private-files")
    val managed = SecureProfileStore.profileFile(root, managedId)
    assertEquals(File(root, "secure-profiles/$managedId.vlk"), managed)
    for (other in listOf(SecureProfileStore.CORE_ARGON, SecureProfileStore.CORE_BETA, SecureProfileStore.CORE_ARGON_RELAY)) {
      assertNotEquals(SecureProfileStore.profileFile(root, other), managed)
    }
  }

  @Test fun differentSubscriptionImportsHaveIndependentRecordsBeforeSelectionCommit() {
    val source = "https://example.test/managed/" + "a".repeat(24)
    assertEquals(FairwayManagedProfileStore.identity(source), FairwayManagedProfileStore.identity(source))
    assertNotEquals(FairwayManagedProfileStore.identity(source), FairwayManagedProfileStore.identity(source.replace("a".repeat(24), "b".repeat(24))))
  }
}
