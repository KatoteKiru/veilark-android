package com.example.veilark.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNoticePolicyTest {
  @Test fun noticesOnlyAdvanceWithTheReleaseCounter() {
    assertFalse(shouldNotifyRelease(0, 0))
    assertFalse(shouldNotifyRelease(65, 65))
    assertFalse(shouldNotifyRelease(64, 65))
    assertTrue(shouldNotifyRelease(66, 65))
  }
}
