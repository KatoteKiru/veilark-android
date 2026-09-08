package com.example.veilark.storage

import org.junit.Assert.*
import org.junit.Test

class MacKeychainTest {
  @Test fun onlyExplicitNotFoundAllowsKeyCreation() {
    assertNull(MacKeychain.decodeExisting(44, "not found"))
    for (status in listOf(1, 36, 128, 255)) {
      assertThrows(IllegalStateException::class.java) { MacKeychain.decodeExisting(status, "denied") }
    }
  }
  @Test fun malformedExistingKeyIsNotReplaced() {
    assertThrows(IllegalStateException::class.java) { MacKeychain.decodeExisting(0, "invalid") }
    assertArrayEquals(ByteArray(32), MacKeychain.decodeExisting(0, "00".repeat(32)))
  }
}
