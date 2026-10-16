package com.example.veilark.profile

import org.junit.Assert.*
import org.junit.Test

class CoreProfileKindTest {
  @Test fun absentChoicePrefersLegacyAndUsesArgonOnlyWhenAlone() {
    assertNull(CoreProfileKind.resolve(null, false, false))
    assertEquals(CoreProfileKind.LEGACY_V1, CoreProfileKind.resolve(null, true, false))
    assertEquals(CoreProfileKind.ARGON_V1, CoreProfileKind.resolve(null, false, true))
    assertEquals(CoreProfileKind.LEGACY_V1, CoreProfileKind.resolve(null, true, true))
  }

  @Test fun explicitChoiceNeverFallsBackForEveryAvailabilityCombination() {
    for (kind in CoreProfileKind.values()) {
      for (legacy in listOf(false, true)) for (argon in listOf(false, true)) {
        val available = if (kind == CoreProfileKind.LEGACY_V1) legacy else argon
        assertEquals(if (available) kind else null,
          CoreProfileKind.resolve(kind.preferenceValue, legacy, argon))
      }
    }
    assertEquals("legacy_v1", CoreProfileKind.LEGACY_V1.preferenceValue)
    assertEquals("argon_v1", CoreProfileKind.ARGON_V1.preferenceValue)
  }

  @Test fun corruptOrUnknownSelectionFailsEvenWhenNoProfilesExist() {
    for (saved in listOf<Any>("", "ARGON_V1", "unknown-secret", 1, true, setOf("argon_v1"))) {
      for (legacy in listOf(false, true)) for (argon in listOf(false, true)) {
        try {
          CoreProfileKind.resolve(saved, legacy, argon)
          fail("Invalid choice must fail")
        } catch (error: IllegalArgumentException) {
          assertEquals("Invalid Veilark Core profile selection.", error.message)
          assertNull(error.cause)
        }
      }
    }
  }

  @Test fun recognizesOnlyExactTopLevelSchemaDespiteOtherEmbeddedMarkers() {
    assertEquals(CoreProfileKind.LEGACY_V1, recognize(
      """{"schema":"veilark-core-beta-profile-v1","name":"veilark-argon-profile-v1","nested":{"schema":"veilark-argon-profile-v1"}}"""))
    assertEquals(CoreProfileKind.ARGON_V1, recognize(
      """{"schema":"veilark-argon-profile-v1","name":"veilark-core-beta-profile-v1"}"""))
    assertEquals(CoreProfileKind.ARGON_V1, recognize(
      """{"\u0073chema":"veilark-argon-profile-v1"}"""))
    for (text in listOf(
      """{"name":"veilark-argon-profile-v1"}""",
      """{"nested":{"schema":"veilark-argon-profile-v1"}}""",
      """{"schema":"prefix-veilark-argon-profile-v1"}""",
      """{"schema":true}""", "[]", "null",
    )) rejected(text.toByteArray())
  }

  @Test fun rejectsDuplicatesAndMalformedJsonWithoutExposingInputOrCause() {
    for (text in listOf(
      """{"schema":"veilark-argon-profile-v1","schema":"veilark-core-beta-profile-v1"}""",
      """{"schema":"veilark-argon-profile-v1","\u0073chema":"veilark-argon-profile-v1"}""",
      """{"schema":"veilark-argon-profile-v1","nested":{"secret":1,"secret":2}}""",
      """{"schema":"veilark-argon-profile-v1",}""",
      """{"schema":"veilark-argon-profile-v1"} secret""",
      """{"schema":"veilark-argon-profile-v1","name":"\ud800"}""",
    )) rejected(text.toByteArray())
  }

  @Test fun enforcesStrictUtf8AndByteLimitIncludingMultibyteCharacters() {
    rejected(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
    rejected(byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))
    rejected(byteArrayOf(0xc2.toByte()))
    val valid = """{"schema":"veilark-argon-profile-v1"}"""
    val exact = valid + " ".repeat(16 * 1024 - valid.toByteArray().size)
    assertEquals(CoreProfileKind.ARGON_V1, recognize(exact))
    rejected((exact + " ").toByteArray())
    val multibyte = """{"schema":"veilark-argon-profile-v1","name":"${"я".repeat(9000)}"}"""
    assertTrue(multibyte.length < 16 * 1024)
    rejected(multibyte.toByteArray())
  }

  private fun recognize(text: String) = CoreProfileKind.recognize(text.toByteArray(Charsets.UTF_8))
  private fun rejected(bytes: ByteArray) {
    try {
      CoreProfileKind.recognize(bytes)
      fail("Invalid profile must fail")
    } catch (error: IllegalArgumentException) {
      assertEquals("Invalid Veilark Core profile.", error.message)
      assertNull(error.cause)
    }
  }
}
