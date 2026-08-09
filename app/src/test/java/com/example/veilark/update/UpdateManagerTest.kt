package com.example.veilark.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
  @Test
  fun acceptsExpectedResumeRange() {
    assertTrue(UpdateManager.contentRangeMatches("bytes 1024-4095/4096", 1024, 4096))
  }

  @Test
  fun rejectsMismatchedOrMalformedResumeRange() {
    assertFalse(UpdateManager.contentRangeMatches("bytes 0-4095/4096", 1024, 4096))
    assertFalse(UpdateManager.contentRangeMatches("bytes 1024-2047/4096", 1024, 4096))
    assertFalse(UpdateManager.contentRangeMatches(null, 1024, 4096))
  }
}
