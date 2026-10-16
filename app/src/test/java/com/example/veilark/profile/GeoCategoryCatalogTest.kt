package com.example.veilark.profile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

class GeoCategoryCatalogTest {
  private fun rejected(block: () -> Unit) {
    try { block(); fail("Expected safe rejection") } catch (_: IllegalArgumentException) { }
  }
  private val config = """{"route":{"final":"proxy"},"dns":{"servers":[]}}"""

  @Test fun firstInstallBrowsesAndPreparesPackagedCategoryWithoutNetwork() = runBlocking {
    val directory = Files.createTempDirectory("geo-bootstrap").toFile()
    try {
      val asset = """{"version":2,"rules":[{"domain_suffix":["telegram.org"]}]}""".toByteArray()
      val blob = GeoCategoryCatalog.gitBlobHash(asset)
      val snapshot = JSONObject().put("version", 1).put("entries", JSONArray().put(JSONObject()
        .put("id", "geosite:telegram").put("revision", "a".repeat(40)).put("blob", blob).put("size", asset.size))).toString().toByteArray()
      val catalog = GeoCategoryCatalog(directory) { name ->
        when (name) {
          "catalog.json" -> snapshot
          "$blob-geosite-telegram.json" -> asset
          else -> error("Unexpected bundle lookup: $name")
        }
      }
      assertEquals("geosite:telegram", catalog.load().single().id)
      assertEquals(1, catalog.prepare("geosite:telegram", "").size)
      assertEquals(listOf("telegram.org", "*.telegram.org"), catalog.trustDirectExclusions("geosite:telegram", ""))
      val existing = File(directory, "geo-categories/catalog.json").readBytes()
      val replacement = GeoCategoryCatalog(directory) { error("Existing catalog must not be replaced") }
      assertEquals("geosite:telegram", replacement.load().single().id)
      assertArrayEquals(existing, File(directory, "geo-categories/catalog.json").readBytes())
    } finally { directory.deleteRecursively() }
  }

  @Test fun categoriesRoundTripAndInvalidPathsReject() {
    val profile = CustomGeoProfile.create("Strategy", "geosite:telegram geoip:de", "geosite:google@!cn geosite:geolocation-!cn")
    assertEquals(profile, CustomGeoProfile.parse(profile.toJson()))
    assertEquals(listOf("geosite:telegram", "geoip:de"), ProfileSelection.routingEntries(profile.directEntries).categories)
    rejected { CustomGeoProfile.create("Unsafe", "geosite:../../secret", "") }
    rejected { CustomGeoProfile.create("Conflict", "geoip:de", "geoip:de") }
  }

  @Test fun compilerUsesRealLocalSourcesAndVpnPriority() {
    val json = JSONObject(ProfileSelection.applyRouting(config, ProfileSelection.ROUTING_MANUAL,
      "geoip:de geosite:telegram", "geosite:google", categoryRuleSets = mapOf(
        "geoip:de" to "/data/de.json", "geosite:telegram" to "/data/telegram.json", "geosite:google" to "/data/google.json")))
    val route = json.getJSONObject("route")
    assertEquals(3, route.getJSONArray("rule_set").length())
    assertEquals("source", route.getJSONArray("rule_set").getJSONObject(0).getString("format"))
    assertEquals("proxy", route.getJSONArray("rules").getJSONObject(1).getString("outbound"))
    assertEquals("bootstrap-dns", json.getJSONObject("dns").getJSONArray("rules").getJSONObject(0).getString("server"))
    rejected { ProfileSelection.applyRouting(config, ProfileSelection.ROUTING_MANUAL, "geoip:de") }
  }

  @Test fun trustPreservesExactVersusSuffixAndRejectsUnsupportedMatchers() {
    val result = GeoCategoryCatalog.trustRules("""{"version":2,"rules":[{"domain":["exact.example"],"domain_suffix":["suffix.example"],"ip_cidr":["192.0.2.0/24"]}]}""")
    assertEquals(listOf("exact.example", "suffix.example", "*.suffix.example", "192.0.2.0/24"), result)
    rejected { GeoCategoryCatalog.trustRules("""{"version":2,"rules":[{"domain_regex":[".*"]}]}""") }
    rejected { GeoCategoryCatalog.trustRules("""{"version":2,"rules":[{"domain_keyword":["bank"]}]}""") }
  }

  @Test fun refreshedCatalogDoesNotInvalidatePreparedLastKnownGoodAndCorruptionFailsClosed() {
    val directory = Files.createTempDirectory("geo-lkg").toFile()
    try {
      val root = File(directory, "geo-categories").also { it.mkdirs() }
      val old = """{"version":2,"rules":[{"domain_suffix":["example.org"]}]}""".toByteArray()
      val blob = GeoCategoryCatalog.gitBlobHash(old)
      fun snapshot(hash: String, size: Int) = JSONObject().put("version", 1).put("entries", JSONArray().put(JSONObject()
        .put("id", "geosite:test").put("revision", "a".repeat(40)).put("blob", hash).put("size", size))).toString()
      File(root, "prepared.json").writeText(snapshot(blob, old.size))
      File(root, "catalog.json").writeText(snapshot("b".repeat(40), old.size + 1))
      val asset = File(root, "$blob-geosite-test.json").also { it.writeBytes(old) }
      val catalog = GeoCategoryCatalog(directory)
      assertEquals(asset.absolutePath, catalog.localPaths("geosite:test", "")["geosite:test"])
      asset.writeBytes(old + 1.toByte())
      rejected { catalog.localPaths("geosite:test", "") }
      rejected { catalog.trustDirectExclusions("geosite:test", "geoip:de") }
    } finally { directory.deleteRecursively() }
  }
}
