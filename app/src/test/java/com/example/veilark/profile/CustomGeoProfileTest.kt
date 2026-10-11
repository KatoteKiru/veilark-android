package com.example.veilark.profile

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class CustomGeoProfileTest {
  private fun rejected(block: () -> Unit) {
    try { block(); fail("Expected safe rejection") } catch (_: IllegalArgumentException) { }
  }

  @Test fun customRoundTripNormalizesAndDeduplicates() {
    val profile = CustomGeoProfile.create(" Банки ", "*.BANK.example bank.example 192.0.2.1", "2001:db8::/32")
    assertEquals("Банки", profile.name)
    assertEquals("bank.example\n192.0.2.1/32", profile.directEntries)
    assertEquals(profile, CustomGeoProfile.parse(profile.toJson()))
  }

  @Test fun acceptsOnlyOwnSchemaWithoutUnsupportedFields() {
    val valid = CustomGeoProfile.create("test", "bank.example", "").toJson()
    rejected { CustomGeoProfile.parse(valid.replace(CustomGeoProfile.SCHEMA, "other-v1")) }
    rejected { CustomGeoProfile.parse(valid.dropLast(1) + ",\"unexpected\":true}") }
    rejected { CustomGeoProfile.parse(valid + "\u0000trailing") }
    rejected { CustomGeoProfile.parse("""{"schema":"veilark-custom-geo-v1","name":"test","direct":["bank.example"]}""") }
    rejected { CustomGeoProfile.create("unsupported", "geosite:ru", "") }
    rejected { CustomGeoProfile.create("bad\u0000name", "bank.example", "") }
  }

  @Test fun invalidAndAmbiguousRulesCannotReachActiveRouting() {
    listOf("https://bank.example/path", "regexp:.*", "2001::::/64", "192.0.2.1/33", "999.0.0.1", "bank.example/path").forEach { rule ->
      rejected { CustomGeoProfile.create("bad", rule, "") }
    }
    rejected { CustomGeoProfile.create("conflict", "*.bank.example", "bank.example") }
    rejected { CustomGeoProfile.create("empty", "", "") }
    rejected { CustomGeoProfile.parse("""{"schema":"veilark-custom-geo-v1","name":"bad","direct":["two.example three.example"]}""") }
    rejected { CustomGeoProfile.parse("{}".repeat(CustomGeoProfile.MAX_BYTES)) }
    rejected { CustomGeoProfile.parse("{\"name\":\"x\"}" + "trailing code") }
    rejected { CustomGeoProfile.parse("[".repeat(17) + "{}" + "]".repeat(17)) }
  }

  @Test fun githubPolicyCanonicalizesOnlyPublicDataFiles() {
    assertEquals("https://raw.githubusercontent.com/owner/repo/main/filter.json", CustomGeoProfileFetcher.canonicalUrl("https://github.com/owner/repo/blob/main/filter.json").toString())
    listOf("http://raw.githubusercontent.com/owner/repo/main/f.json", "https://127.0.0.1/f.json", "https://raw.githubusercontent.com.evil.test/o/r/main/f.json", "https://user@github.com/o/r/blob/main/f.json", "https://github.com/o/r/blob/main/f.json?token=secret", "https://github.com/o/r/blob/main/../f.json", "https://github.com/o/r/blob/main/%2e%2e/f.json", "https://github.com/o/r/blob/main/script.kt").forEach { url ->
      rejected { CustomGeoProfileFetcher.canonicalUrl(url) }
    }
  }

  @Test fun atomicDraftPersistsIndependentlyFromRoutingAndRejectsCorruption() {
    val directory = Files.createTempDirectory("veilark-custom-geo-test").toFile()
    try {
      val store = CustomGeoProfileStore(directory)
      assertNull(store.load())
      val old = CustomGeoProfile.create("old", "old.example", "")
      store.save(old)
      assertEquals(old, store.load())
      val newer = CustomGeoProfile.create("new", "new.example", "")
      store.save(newer)
      assertEquals(newer, store.load())
      assertEquals(listOf(old, newer), store.list())
      assertEquals(1, directory.listFiles()!!.size)
      directory.resolve("custom-geo-profile.json").writeText("not JSON")
      rejected { store.load() }
      rejected { store.save(old) }
      rejected { store.remove("new") }
      assertEquals("not JSON", directory.resolve("custom-geo-profile.json").readText())
    } finally { directory.deleteRecursively() }
  }

  @Test fun sameExactNameReplacesAndDeleteRetainsOtherScenarios() {
    val directory = Files.createTempDirectory("veilark-custom-geo-names").toFile()
    try {
      val store = CustomGeoProfileStore(directory)
      val first = CustomGeoProfile.create("Work", "work.example", "")
      val lower = CustomGeoProfile.create("work", "lower.example", "")
      val replacement = CustomGeoProfile.create("Work", "new.example", "")
      store.save(first)
      store.save(lower)
      store.save(replacement)
      assertEquals(listOf(lower, replacement), store.list())
      assertEquals(replacement, store.load())
      store.remove("Work")
      assertEquals(listOf(lower), store.list())
      store.remove("missing")
      assertEquals(listOf(lower), store.list())
      store.remove("work")
      assertTrue(store.list().isEmpty())
      assertNull(store.load())
    } finally { directory.deleteRecursively() }
  }

  @Test fun migratesSingleOwnDraftOnlyAfterSuccessfulSave() {
    val directory = Files.createTempDirectory("veilark-custom-geo-migration").toFile()
    try {
      val first = CustomGeoProfile.create("legacy", "legacy.example", "")
      val file = directory.resolve("custom-geo-profile.json")
      file.writeText(first.toJson())
      val original = file.readText()
      val store = CustomGeoProfileStore(directory)
      assertEquals(listOf(first), store.list())
      assertEquals(original, file.readText())
      val second = CustomGeoProfile.create("new", "new.example", "")
      store.save(second)
      assertEquals(listOf(first, second), CustomGeoProfileStore(directory).list())
      assertEquals(1, directory.listFiles()!!.size)
    } finally { directory.deleteRecursively() }
  }

  @Test fun profileCountLimitRejectsWithoutReplacingSavedFile() {
    val directory = Files.createTempDirectory("veilark-custom-geo-limit").toFile()
    try {
      val store = CustomGeoProfileStore(directory)
      repeat(CustomGeoProfileStore.MAX_PROFILES) { store.save(CustomGeoProfile.create("$it", "bank.example", "")) }
      val file = directory.resolve("custom-geo-profile.json")
      val before = file.readText()
      rejected { store.save(CustomGeoProfile.create("extra", "bank.example", "")) }
      assertEquals(before, file.readText())
      store.save(CustomGeoProfile.create("0", "updated.example", ""))
      assertEquals(CustomGeoProfileStore.MAX_PROFILES, store.list().size)
      assertEquals("updated.example", store.load()!!.directEntries)
    } finally { directory.deleteRecursively() }
  }

  @Test fun totalStoreSizeLimitRetainsPreviousProfiles() {
    val directory = Files.createTempDirectory("veilark-custom-geo-bytes").toFile()
    try {
      val store = CustomGeoProfileStore(directory)
      val rules = (0 until 2000).joinToString("\n") { "${"a".repeat(50)}.${"b".repeat(50)}.r$it.example" }
      repeat(4) { store.save(CustomGeoProfile.create("$it", rules, "")) }
      val file = directory.resolve("custom-geo-profile.json")
      val before = file.readText()
      rejected { store.save(CustomGeoProfile.create("extra", rules, "")) }
      assertEquals(before, file.readText())
      assertEquals(4, store.list().size)
    } finally { directory.deleteRecursively() }
  }

  @Test fun damagedStoreDepthAndDuplicateNamesCannotBeOverwritten() {
    val directory = Files.createTempDirectory("veilark-custom-geo-corrupt").toFile()
    try {
      val store = CustomGeoProfileStore(directory)
      val profile = CustomGeoProfile.create("same", "bank.example", "")
      val file = directory.resolve("custom-geo-profile.json")
      val damaged = listOf(
        "[".repeat(17) + "{}" + "]".repeat(17),
        """{"schema":"veilark-custom-geo-store-v1","profiles":[${profile.toJson()},${profile.toJson()}]}""",
        """{"schema":"veilark-custom-geo-store-v1","profiles":[]} trailing""",
      )
      damaged.forEach { text ->
        file.writeText(text)
        rejected { store.list() }
        rejected { store.save(profile) }
        assertEquals(text, file.readText())
      }
    } finally { directory.deleteRecursively() }
  }

  @Test fun fetchRejectsRedirectsOversizedStreamingAndInvalidUtf8() {
    val source = "https://raw.githubusercontent.com/o/r/main/f.json"
    val valid = CustomGeoProfile.create("test", "bank.example", "").toJson().toByteArray()
    val success = FakeConnection(valid)
    assertEquals("test", CustomGeoProfileFetcher.fetchWithConnection(source) { success }.name)
    assertFalse(success.instanceFollowRedirects)
    assertTrue(success.closed)
    val redirect = FakeConnection(valid, 302)
    rejected { CustomGeoProfileFetcher.fetchWithConnection(source) { redirect } }
    assertTrue(redirect.closed)
    rejected { CustomGeoProfileFetcher.fetchWithConnection(source) { FakeConnection(ByteArray(CustomGeoProfile.MAX_BYTES + 1)) } }
    rejected { CustomGeoProfileFetcher.fetchWithConnection(source) { FakeConnection(byteArrayOf(0xc3.toByte(), 0x28)) } }
  }

  @Test fun importsGithubTextDataWithExplicitDirectionAndRejectsCode() {
    val source = "https://github.com/o/r/blob/main/rules.txt"
    val rules = CustomGeoProfileFetcher.fetchRulesWithConnection(source) {
      FakeConnection("# public list\nbank.example\n192.0.2.0/24\n".toByteArray())
    }
    assertEquals("bank.example\n192.0.2.0/24", rules)
    val direct = CustomGeoProfile.create("Direct scenario", rules, "vpn.example")
    val vpn = CustomGeoProfile.create("VPN scenario", "direct.example", rules)
    assertEquals(rules, direct.directEntries)
    assertEquals(rules, vpn.vpnEntries)
    rejected {
      CustomGeoProfileFetcher.fetchRulesWithConnection(source) {
        FakeConnection("include:other\nregexp:.*".toByteArray())
      }
    }
    rejected { CustomGeoProfileFetcher.fetchRulesWithConnection(source) { FakeConnection("#!/bin/sh\necho execute".toByteArray()) } }
  }

  private class FakeConnection(private val body: ByteArray, private val status: Int = 200) : HttpURLConnection(URL("https://raw.githubusercontent.com/o/r/main/f.json")) {
    var closed = false
    override fun connect() { }
    override fun disconnect() { closed = true }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getContentLengthLong() = -1L
    override fun getInputStream() = ByteArrayInputStream(body)
  }
}
