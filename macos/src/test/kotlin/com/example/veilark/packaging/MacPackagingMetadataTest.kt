package com.example.veilark.packaging

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MacPackagingMetadataTest {
  @Test
  fun pinsOnlyAuditedStableCoresAndExtractedBinaries() {
    val root = JSONObject(File("vendor/UPSTREAM.json").readText())
    val singBox = root.getJSONObject("sing-box")
    val trust = root.getJSONObject("trusttunnel_client")

    assertEquals("1.13.19", singBox.getString("version"))
    assertEquals("1.0.49", trust.getString("version"))
    assertTrue(singBox.getJSONObject("binarySha256").getString("arm64").matches(HEX_SHA256))
    assertTrue(singBox.getJSONObject("binarySha256").getString("amd64").matches(HEX_SHA256))
    assertTrue(trust.getString("binarySha256").matches(HEX_SHA256))
  }

  @Test
  fun fetchScriptCannotOverridePinnedTrustVersionAndHasPostExtractChecks() {
    val script = File("scripts/fetch-engines.sh").readText()
    assertFalse(script.contains("TRUST_TUNNEL_VERSION"))
    assertTrue(script.contains("verify_sha256 \"${'$'}SING_BOX_BINARY_SHA256\" \"${'$'}COMMON/sing-box\""))
    assertTrue(script.contains("verify_sha256 \"${'$'}TRUST_BINARY_SHA256\" \"${'$'}COMMON/trusttunnel_client\""))
  }

  companion object {
    private val HEX_SHA256 = Regex("[0-9a-f]{64}")
  }
}
