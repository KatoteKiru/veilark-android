package com.example.veilark.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeoUpdateCandidateSelectorTest {
  private fun manifest(generatedAt: String) = GeoUpdateManifest(
    generatedAt,
    GeoUpdateAsset("a".repeat(64), 101),
    GeoUpdateAsset("b".repeat(64), 202),
  )

  @Test
  fun usesBackupManifestWhenPrimaryAssetsFailValidation() {
    val tried = mutableListOf<String>()
    val selected = GeoUpdateCandidateSelector.select(
      urls = listOf("primary", "backup"),
      active = manifest("20260927T000000Z"),
      loadManifest = { url ->
        if (url == "primary") manifest("20260928T100000Z")
        else manifest("20260928T040000Z")
      },
      validateCandidate = { candidate ->
        tried += candidate.generatedAt
        if (candidate.generatedAt == "20260928T100000Z") {
          error("Primary assets unavailable")
        }
      },
    )

    assertEquals("20260928T040000Z", selected.generatedAt)
    assertEquals(listOf("20260928T100000Z", "20260928T040000Z"), tried)
  }

  @Test
  fun rejectsBackupOlderThanActiveGeneration() {
    val tried = mutableListOf<String>()
    assertThrows(IllegalStateException::class.java) {
      GeoUpdateCandidateSelector.select(
        urls = listOf("primary", "backup"),
        active = manifest("20260928T080000Z"),
        loadManifest = { url ->
          if (url == "primary") manifest("20260928T100000Z")
          else manifest("20260928T040000Z")
        },
        validateCandidate = { candidate ->
          tried += candidate.generatedAt
          error("Primary assets unavailable")
        },
      )
    }
    assertEquals(listOf("20260928T100000Z"), tried)
  }

  @Test
  fun rejectsChangedAssetsAtSameTimestampBeforeDownload() {
    val active = manifest("20260928T100000Z")
    var downloaded = false
    assertThrows(IllegalStateException::class.java) {
      GeoUpdateCandidateSelector.select(
        urls = listOf("primary"),
        active = active,
        loadManifest = {
          active.copy(geoIp = active.geoIp.copy(sha256 = "c".repeat(64)))
        },
        validateCandidate = { downloaded = true },
      )
    }
    assertEquals(false, downloaded)
  }
}
