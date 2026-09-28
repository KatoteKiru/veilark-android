package com.example.veilark.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeoUpdateManifestTest {
  @Test
  fun parsesBoundedPairAndRejectsRollback() {
    val manifest = GeoUpdateManifest.parse(validManifest(), 1_000)

    assertEquals("20260928T042030Z", manifest.generatedAt)
    assertEquals(101L, manifest.geoIp.size)
    assertEquals(202L, manifest.geoSite.size)
    manifest.requireCompatibleWith(manifest.copy())
    assertThrows(IllegalArgumentException::class.java) {
      manifest.requireCompatibleWith(manifest.copy(generatedAt = "20260929T000000Z"))
    }
  }

  @Test
  fun sameTimestampRequiresIdenticalHashesAndSizes() {
    val active = GeoUpdateManifest.parse(validManifest(), 1_000)
    active.copy(geoIp = active.geoIp.copy(sha256 = "c".repeat(64)))
      .let { candidate ->
        assertThrows(IllegalArgumentException::class.java) {
          candidate.requireCompatibleWith(active)
        }
      }
    active.copy(geoSite = active.geoSite.copy(size = active.geoSite.size + 1))
      .let { candidate ->
        assertThrows(IllegalArgumentException::class.java) {
          candidate.requireCompatibleWith(active)
        }
      }
  }

  @Test
  fun rejectsMalformedHashAndOversizedAsset() {
    assertThrows(IllegalArgumentException::class.java) {
      GeoUpdateManifest.parse(validManifest().replace("a".repeat(64), "nope"), 1_000)
    }
    assertThrows(IllegalArgumentException::class.java) {
      GeoUpdateManifest.parse(validManifest(), 200)
    }
  }

  @Test
  fun requiresBothNamedAssets() {
    assertThrows(org.json.JSONException::class.java) {
      GeoUpdateManifest.parse(
        validManifest().replace("geosite-category-ru.srs", "other.srs"),
        1_000,
      )
    }
  }

  private fun validManifest(): String =
    """{"schema":1,"generatedAt":"20260928T042030Z","assets":{
      "geoip-ru.srs":{"sha256":"${"a".repeat(64)}","size":101},
      "geosite-category-ru.srs":{"sha256":"${"b".repeat(64)}","size":202}}}
    """.trimIndent()
}
