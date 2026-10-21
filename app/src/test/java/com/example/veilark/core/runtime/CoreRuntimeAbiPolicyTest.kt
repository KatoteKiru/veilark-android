package com.example.veilark.core.runtime

import org.junit.Assert.*
import org.junit.Test

class CoreRuntimeAbiPolicyTest {
  @Test fun productRequiresPrimaryArm64EvenWhenBridgeExists() {
    assertTrue(coreRuntimeAbiSupported(listOf("arm64-v8a", "armeabi-v7a"), false))
    assertFalse(coreRuntimeAbiSupported(listOf("x86_64", "arm64-v8a"), false))
    assertFalse(coreRuntimeAbiSupported(emptyList(), false))
  }

  @Test fun isolatedLabStillRequiresAnActuallyAdvertisedArm64Abi() {
    assertTrue(coreRuntimeAbiSupported(listOf("x86_64", "arm64-v8a"), true))
    assertFalse(coreRuntimeAbiSupported(listOf("x86_64"), true))
    assertFalse(coreRuntimeAbiSupported(emptyList(), true))
  }
}
