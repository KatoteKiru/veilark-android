package com.example.veilark.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class MacUpdateClientTest {
  @Test
  fun candidateMetadataIsNewerThanPublished105() {
    assertEquals("1.0.6", MacUpdateClient.CURRENT_VERSION)
    assertEquals(10_006, MacUpdateClient.CURRENT_BUILD)
  }

  @Test
  fun requiresGreaterBuildAndNonDecreasingVersion() {
    val current = MacUpdate(
      version = MacUpdateClient.CURRENT_VERSION,
      build = MacUpdateClient.CURRENT_BUILD,
      architecture = "universal",
      url = "https://updates.example.com/Veilark-${MacUpdateClient.CURRENT_VERSION}.dmg",
      sha256 = "c".repeat(64),
      notes = "Current release",
    )
    assertFalse(MacUpdateClient.isNewer(current))
    assertFalse(MacUpdateClient.isNewer(current.copy(build = MacUpdateClient.CURRENT_BUILD - 1)))
    assertTrue(MacUpdateClient.isNewer(current.copy(build = MacUpdateClient.CURRENT_BUILD + 1)))
    assertFalse(
      MacUpdateClient.isNewer(
        current.copy(version = "0.9.9", build = MacUpdateClient.CURRENT_BUILD + 1),
      ),
    )
  }

  @Test
  fun evaluatesPublishedAndCandidateAcrossHistoricalBuilds() {
    val published = MacUpdate(
      version = "1.0.5",
      build = 10_005,
      architecture = "arm64",
      url = "https://updates.example.com/Veilark-1.0.5.dmg",
      sha256 = "d".repeat(64),
      notes = "Published release",
    )
    listOf(
      "1.0.2" to 10_002,
      "1.0.3" to 10_003,
      "1.0.4" to 10_004,
    ).forEach { (version, build) ->
      assertTrue(MacUpdateClient.isNewerThan(published, version, build))
    }
    assertFalse(MacUpdateClient.isNewerThan(published, "1.0.5", 10_005))
    assertFalse(MacUpdateClient.isNewerThan(published, "1.0.6", 10_006))

    val candidate = published.copy(version = "1.0.6", build = 10_006)
    listOf(
      "1.0.2" to 10_002,
      "1.0.3" to 10_003,
      "1.0.4" to 10_004,
      "1.0.5" to 10_005,
    ).forEach { (version, build) ->
      assertTrue(MacUpdateClient.isNewerThan(candidate, version, build))
    }
  }

  @Test
  fun resolvesInstalledAppBundleFromPackagedResourcesOnly() {
    val resources = File("/Applications/Veilark.app/Contents/app/resources")
    assertEquals(
      File("/Applications/Veilark.app").canonicalFile,
      MacUpdateClient.appBundleFromResources(resources),
    )
    assertNull(MacUpdateClient.appBundleFromResources(File("/tmp/veilark/resources")))
  }

  @Test
  fun verifiesSignedMacManifest() {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val update = MacUpdate(
      version = "1.0.2",
      build = 10_002,
      architecture = "universal",
      url = "https://updates.example.com/Veilark-1.0.2.dmg",
      sha256 = "a".repeat(64),
      notes = "Stable routing and lifecycle fixes",
    )
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(pair.private)
    signer.update(MacUpdateClient.canonicalPayload(update))
    val signature = Base64.getEncoder().encodeToString(signer.sign())
    val manifest = JSONObject()
      .put("schemaVersion", 1)
      .put("platform", "macos")
      .put("version", update.version)
      .put("build", update.build)
      .put("architecture", update.architecture)
      .put("url", update.url)
      .put("sha256", update.sha256)
      .put("notes", update.notes)
      .put("signature", signature)
      .toString()

    val parsed = MacUpdateClient.parseAndVerify(
      manifest,
      Base64.getEncoder().encodeToString(pair.public.encoded),
    )
    assertEquals(update.copy(signature = signature), parsed)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsManifestChangedAfterSigning() {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val update = MacUpdate(
      version = "1.0.2",
      build = 10_002,
      architecture = "universal",
      url = "https://updates.example.com/Veilark-1.0.2.dmg",
      sha256 = "b".repeat(64),
      notes = "Original notes",
    )
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(pair.private)
    signer.update(MacUpdateClient.canonicalPayload(update))
    val manifest = JSONObject()
      .put("schemaVersion", 1)
      .put("platform", "macos")
      .put("version", update.version)
      .put("build", update.build + 1)
      .put("architecture", update.architecture)
      .put("url", update.url)
      .put("sha256", update.sha256)
      .put("notes", update.notes)
      .put("signature", Base64.getEncoder().encodeToString(signer.sign()))
      .toString()

    MacUpdateClient.parseAndVerify(
      manifest,
      Base64.getEncoder().encodeToString(pair.public.encoded),
    )
  }
}
