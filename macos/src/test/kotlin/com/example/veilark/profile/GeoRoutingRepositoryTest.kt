package com.example.veilark.profile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

class GeoRoutingRepositoryTest {
  @get:Rule
  val folder = TemporaryFolder()

  @Test
  fun refreshUsesStandardHttpsAndPublishesManifestVerifiedAssets() = runBlocking {
    val geoIp = "geoip-srs".toByteArray()
    val geoSite = "geosite-srs".toByteArray()
    val responses = mutableMapOf(
      MANIFEST_443 to manifest(geoIp, geoSite),
      GEOIP_443 to geoIp,
      GEOSITE_443 to geoSite,
    )
    val requested = mutableListOf<String>()
    val root = folder.newFolder("geo")
    val repository = repository(root, responses, requested)

    val active = repository.refreshFromRemote()

    assertEquals(listOf(MANIFEST_443, GEOIP_443, GEOSITE_443), requested)
    assertArrayEquals(geoIp, active.geoIpSrs.readBytes())
    assertArrayEquals(geoSite, active.geoSiteSrs.readBytes())
    assertTrue(File(root, "current.json").isFile)
    assertEquals(active, repository.currentOrBundled())
  }

  @Test
  fun checksumMismatchKeepsPreviousVerifiedGeneration() = runBlocking {
    val geoIp = "known-geoip".toByteArray()
    val geoSite = "known-geosite".toByteArray()
    val responses = mutableMapOf(
      MANIFEST_443 to manifest(geoIp, geoSite),
      GEOIP_443 to geoIp,
      GEOSITE_443 to geoSite,
    )
    val root = folder.newFolder("geo")
    val repository = repository(root, responses, mutableListOf())
    val previous = repository.refreshFromRemote()
    val previousManifest = File(root, "current.json").readText()
    responses[MANIFEST_443] = manifest(geoIp, geoSite, generatedAt = "20260929T042030Z")
    responses[GEOIP_443] = "tampered".toByteArray()

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.refreshFromRemote() }
    }

    assertEquals(previous, repository.currentOrBundled())
    assertArrayEquals(geoIp, previous.geoIpSrs.readBytes())
    assertEquals(previousManifest, File(root, "current.json").readText())
    assertFalse(File(root, "generations").listFiles().orEmpty().any { it.name.startsWith(".staging-") })
  }

  @Test
  fun olderManifestCannotReplaceInstalledGeneration() = runBlocking {
    val geoIp = "known-geoip".toByteArray()
    val geoSite = "known-geosite".toByteArray()
    val responses = mutableMapOf(
      MANIFEST_443 to manifest(geoIp, geoSite),
      GEOIP_443 to geoIp,
      GEOSITE_443 to geoSite,
    )
    val requested = mutableListOf<String>()
    val root = folder.newFolder("geo")
    val repository = repository(root, responses, requested)
    val active = repository.refreshFromRemote()
    val installedManifest = File(root, "current.json").readText()
    requested.clear()
    responses[MANIFEST_443] = manifest(geoIp, geoSite, generatedAt = "20260927T042030Z")

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.refreshFromRemote() }
    }

    assertEquals(MANIFEST_443, requested.first())
    assertTrue(requested.contains(MANIFEST_2096))
    assertFalse(requested.contains(GEOIP_443))
    assertEquals(active, repository.currentOrBundled())
    assertEquals(installedManifest, File(root, "current.json").readText())
  }

  @Test
  fun changedFilesRequireNewGenerationTimestamp() = runBlocking {
    val geoIp = "known-geoip".toByteArray()
    val geoSite = "known-geosite".toByteArray()
    val responses = mutableMapOf(
      MANIFEST_443 to manifest(geoIp, geoSite),
      GEOIP_443 to geoIp,
      GEOSITE_443 to geoSite,
    )
    val requested = mutableListOf<String>()
    val root = folder.newFolder("geo")
    val repository = repository(root, responses, requested)
    val active = repository.refreshFromRemote()
    requested.clear()
    responses[MANIFEST_443] = manifest("changed".toByteArray(), geoSite)

    assertThrows(IllegalStateException::class.java) {
      runBlocking { repository.refreshFromRemote() }
    }

    assertEquals(MANIFEST_443, requested.first())
    assertTrue(requested.contains(MANIFEST_2096))
    assertFalse(requested.contains(GEOIP_443))
    assertEquals(active, repository.currentOrBundled())
  }

  @Test
  fun usesCoherentFallbackWhenPrimaryAssetsDoNotMatchItsManifest() = runBlocking {
    val primaryGeoIp = "new-primary".toByteArray()
    val fallbackGeoIp = "older-fallback".toByteArray()
    val geoSite = "geosite-srs".toByteArray()
    val responses = mutableMapOf(
      MANIFEST_443 to manifest(primaryGeoIp, geoSite, generatedAt = "20260929T042030Z"),
      GEOIP_443 to "bad-primary".toByteArray(),
      MANIFEST_2096 to manifest(fallbackGeoIp, geoSite),
      GEOIP_2096 to fallbackGeoIp,
      GEOSITE_2096 to geoSite,
    )
    val requested = mutableListOf<String>()
    val root = folder.newFolder("geo")
    val repository = repository(root, responses, requested)

    val active = repository.refreshFromRemote()

    assertTrue(requested.indexOf(MANIFEST_2096) > requested.indexOf(MANIFEST_443))
    assertArrayEquals(fallbackGeoIp, active.geoIpSrs.readBytes())
    assertArrayEquals(geoSite, active.geoSiteSrs.readBytes())
    assertTrue(File(root, "current.json").readText().contains("20260928T042030Z"))
  }

  @Test
  fun unchangedManifestDoesNotReDownloadOrRecompile() = runBlocking {
    val geoIp = "known-geoip".toByteArray()
    val geoSite = "known-geosite".toByteArray()
    val responses = mutableMapOf(
      MANIFEST_443 to manifest(geoIp, geoSite),
      GEOIP_443 to geoIp,
      GEOSITE_443 to geoSite,
    )
    val requested = mutableListOf<String>()
    val repository = repository(folder.newFolder("geo"), responses, requested)
    val first = repository.refreshFromRemote()
    requested.clear()

    assertEquals(first, repository.refreshFromRemote())
    assertEquals(listOf(MANIFEST_443), requested)
  }

  @Test
  fun previousGenerationRemainsAvailableAfterRefresh() = runBlocking {
    val firstGeoIp = "first-geoip".toByteArray()
    val secondGeoIp = "second-geoip".toByteArray()
    val geoSite = "geosite-srs".toByteArray()
    val responses = mutableMapOf(
      MANIFEST_443 to manifest(firstGeoIp, geoSite),
      GEOIP_443 to firstGeoIp,
      GEOSITE_443 to geoSite,
    )
    val repository = repository(folder.newFolder("geo"), responses, mutableListOf())
    val first = repository.refreshFromRemote()
    responses[MANIFEST_443] = manifest(secondGeoIp, geoSite, generatedAt = "20260929T042030Z")
    responses[GEOIP_443] = secondGeoIp

    val second = repository.refreshFromRemote()

    assertArrayEquals(firstGeoIp, first.geoIpSrs.readBytes())
    assertArrayEquals(secondGeoIp, second.geoIpSrs.readBytes())
    assertEquals(second, repository.currentOrBundled())
  }

  private fun repository(
    root: File,
    responses: MutableMap<String, ByteArray>,
    requested: MutableList<String>,
  ) = GeoRoutingRepository(
    root = root,
    connectionFactory = { uri ->
      requested += uri.toString()
      FakeConnection(uri.toURL(), responses[uri.toString()])
    },
    singBoxProvider = { File(root, "unused-sing-box") },
    ruleSetDecompiler = { _, source, destination ->
      destination.writeText(
        if (source.name == "geoip-ru.srs") {
          """{"version":3,"rules":[{"ip_cidr":["5.136.0.0/13","2a00:f480::/29"]}]}"""
        } else {
          """{"version":3,"rules":[{"domain":["example.ru"]}]}"""
        },
      )
    },
  )

  private fun manifest(
    geoIp: ByteArray,
    geoSite: ByteArray,
    generatedAt: String = "20260928T042030Z",
  ): ByteArray =
    """
    {
      "schema":1,
      "generatedAt":"$generatedAt",
      "assets":{
        "geoip-ru.srs":{"sha256":"${sha256(geoIp)}","size":${geoIp.size}},
        "geosite-category-ru.srs":{"sha256":"${sha256(geoSite)}","size":${geoSite.size}}
      }
    }
    """.trimIndent().toByteArray()

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

  private class FakeConnection(url: URL, private val body: ByteArray?) : HttpURLConnection(url) {
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun getResponseCode(): Int = if (body == null) HTTP_NOT_FOUND else HTTP_OK
    override fun getContentLengthLong(): Long = body?.size?.toLong() ?: -1L
    override fun getInputStream() = ByteArrayInputStream(body ?: error("No response body"))
  }

  private companion object {
    const val MANIFEST_443 = "https://sub.senyasenyavski.uk/veilark/geo/current/manifest.json"
    const val MANIFEST_2096 = "https://nl2.senyasenyavski.uk:2096/veilark/geo/current/manifest.json"
    const val GEOIP_443 = "https://sub.senyasenyavski.uk/veilark/geo/current/geoip-ru.srs"
    const val GEOIP_2096 = "https://nl2.senyasenyavski.uk:2096/veilark/geo/current/geoip-ru.srs"
    const val GEOSITE_443 = "https://sub.senyasenyavski.uk/veilark/geo/current/geosite-category-ru.srs"
    const val GEOSITE_2096 = "https://nl2.senyasenyavski.uk:2096/veilark/geo/current/geosite-category-ru.srs"
  }
}
