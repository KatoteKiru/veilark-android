package com.example.veilark.profile

import org.junit.Assert.*
import org.junit.Test

class CoreEnrollmentSelectionTest {
  @Test fun legacySelectionHasNoEnrollmentIdentity() {
    assertNull(CoreProfileStore.enrollmentIdentity(emptyMap<String, Any>()))
    assertNull(CoreProfileStore.enrollmentIdentity(mapOf("core_profile_kind" to CoreProfileKind.ARGON_V1.preferenceValue)))
  }

  @Test fun canonicalProtectedSelectionPreservesBothIdentities() {
    val saved = mapOf("core_profile_kind" to CoreProfileKind.ARGON_V1.preferenceValue,
      "core_enrollment_issuer" to "issuer-1", "core_enrollment_profile" to "profile_2")
    assertEquals("issuer-1" to "profile_2", CoreProfileStore.enrollmentIdentity(saved))
  }

  @Test fun partialCorruptOrOtherKindSelectionNeverFallsBackToRaw() {
    val valid = mapOf<String, Any>("core_profile_kind" to CoreProfileKind.ARGON_V1.preferenceValue,
      "core_enrollment_issuer" to "issuer-1", "core_enrollment_profile" to "profile_2")
    val invalid = listOf(valid - "core_enrollment_issuer", valid - "core_enrollment_profile",
      valid - "core_profile_kind", valid + ("core_profile_kind" to CoreProfileKind.LEGACY_V1.preferenceValue),
      valid + ("core_enrollment_profile" to "../other"), valid + ("core_enrollment_issuer" to 4),
      valid + ("core_enrollment_profile" to "x".repeat(49)))
    invalid.forEach { saved -> assertThrows(IllegalStateException::class.java) { CoreProfileStore.enrollmentIdentity(saved) } }
  }
}
