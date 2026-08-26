package com.example.veilark.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class MacUpdateClientTest {
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
    val manifest = JSONObject()
      .put("schemaVersion", 1)
      .put("platform", "macos")
      .put("version", update.version)
      .put("build", update.build)
      .put("architecture", update.architecture)
      .put("url", update.url)
      .put("sha256", update.sha256)
      .put("notes", update.notes)
      .put("signature", Base64.getEncoder().encodeToString(signer.sign()))
      .toString()

    val parsed = MacUpdateClient.parseAndVerify(
      manifest,
      Base64.getEncoder().encodeToString(pair.public.encoded),
    )
    assertEquals(update, parsed)
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
