package com.example.veilark.update

import org.junit.Assert.assertEquals
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

  @Test
  fun downloadProgressIsNormalizedAndClamped() {
    assertEquals(0.25f, UpdateDownloadProgress(256, 1024).fraction, 0.001f)
    assertEquals(0f, UpdateDownloadProgress(-1, 1024).fraction, 0.001f)
    assertEquals(1f, UpdateDownloadProgress(2048, 1024).fraction, 0.001f)
    assertEquals(0f, UpdateDownloadProgress(1, 0).fraction, 0.001f)
  }

  @Test
  fun signedV2PayloadIncludesUtf8ReleaseNotesWithoutAmbiguousDelimiters() {
    val update = AppUpdate(
      versionCode = 35,
      versionName = "0.8.0-rc8",
      apkUrl = "https://updates.example.test/veilark/veilark-0.8.0-rc8.apk",
      sha256 = "A".repeat(64),
      size = 1234,
      notes = "Trust split\nOTA внутри приложения",
    )

    val payload = UpdateManager.canonicalPayloadV2(update).decodeToString()

    assertTrue(payload.startsWith("17:veilark-update-v2\n"))
    assertTrue(payload.endsWith("49:Trust split\nOTA внутри приложения\n"))
  }

  @Test
  fun acceptsPrivateOtaWhenSignatureSchemeChangesButCertificateIsStable() {
    val legacyPrivateSigner = "PRIVATE-LEGACY-SIGNER"

    assertTrue(
      UpdateManager.signingLineageAccepts(
        installedCurrent = setOf(legacyPrivateSigner),
        installedHasMultipleSigners = false,
        archiveCurrent = setOf(legacyPrivateSigner),
        archiveHistory = setOf(legacyPrivateSigner),
      ),
    )
  }

  @Test
  fun rejectsOssSignerForPrivateInstallation() {
    assertFalse(
      UpdateManager.signingLineageAccepts(
        installedCurrent = setOf("PRIVATE-SIGNER"),
        installedHasMultipleSigners = false,
        archiveCurrent = setOf("OSS-SIGNER"),
        archiveHistory = setOf("OSS-SIGNER"),
      ),
    )
  }

  @Test
  fun acceptsDeclaredPrivateSigningRotation() {
    assertTrue(
      UpdateManager.signingLineageAccepts(
        installedCurrent = setOf("OLD-PRIVATE-SIGNER"),
        installedHasMultipleSigners = false,
        archiveCurrent = setOf("NEW-PRIVATE-SIGNER"),
        archiveHistory = setOf("OLD-PRIVATE-SIGNER", "NEW-PRIVATE-SIGNER"),
      ),
    )
  }

  @Test
  fun multipleSignerInstallRequiresExactCurrentSet() {
    assertTrue(
      UpdateManager.signingLineageAccepts(
        installedCurrent = setOf("A", "B"),
        installedHasMultipleSigners = true,
        archiveCurrent = setOf("A", "B"),
        archiveHistory = setOf("A", "B"),
      ),
    )
    assertFalse(
      UpdateManager.signingLineageAccepts(
        installedCurrent = setOf("A", "B"),
        installedHasMultipleSigners = true,
        archiveCurrent = setOf("A"),
        archiveHistory = setOf("A", "B"),
      ),
    )
  }
}
