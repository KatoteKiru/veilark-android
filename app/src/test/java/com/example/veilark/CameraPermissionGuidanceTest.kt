package com.example.veilark

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionGuidanceTest {
  @Test
  fun firstDenialOffersRetryEvenWhenPlatformHidesRationale() {
    assertEquals(
      CameraPermissionGuidance.RATIONALE,
      cameraPermissionGuidance(deniedRequestCount = 1, shouldShowRationale = false),
    )
  }

  @Test
  fun normalDenialWithRationaleOffersRetry() {
    assertEquals(
      CameraPermissionGuidance.RATIONALE,
      cameraPermissionGuidance(deniedRequestCount = 2, shouldShowRationale = true),
    )
  }

  @Test
  fun permanentDenialOffersAppSettingsAfterASecondRequest() {
    assertEquals(
      CameraPermissionGuidance.APP_SETTINGS,
      cameraPermissionGuidance(deniedRequestCount = 2, shouldShowRationale = false),
    )
  }
}
