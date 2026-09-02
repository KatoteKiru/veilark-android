package com.example.veilark.packaging

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.Properties

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

  @Test
  fun packageRequiresTheNativeInAppUpdater() {
    val build = File("build.gradle.kts").readText()
    val inventory = File("scripts/verify-packaged-dmg.sh").readText()
    assertTrue(build.contains("val compileUpdater by tasks.registering"))
    assertTrue(build.contains("veilark-updater.swift"))
    assertTrue(inventory.contains("veilark-updater"))
  }

  @Test
  fun nativeUpdaterReceivesTheGatekeeperReleaseFlag() {
    val build = File("build.gradle.kts").readText()
    val updaterTask = build.substringAfter("val compileUpdater by tasks.registering")
      .substringBefore("onlyIf { isMacOs }")
    assertTrue(updaterTask.contains("VEILARK_REQUIRE_GATEKEEPER"))
    assertTrue(updaterTask.contains("macosOtaRequireGatekeeper"))
  }

  @Test
  fun helperTreatsAStalePidAsAnIdempotentStop() {
    val helper = File("helper/veilark-helper.swift").readText()
    val stalePidBranch = helper.substringAfter("guard isManagedProcess(pid) else {")
      .substringBefore("}")
    assertTrue(stalePidBranch.contains("clearPidFile()"))
    assertTrue(stalePidBranch.contains("return"))
    assertFalse(stalePidBranch.contains("fail("))
  }

  @Test
  fun helperV6FailsClosedForPidPersistenceAndUnstoppableChild() {
    val helper = File("helper/veilark-helper.swift").readText()
    val controller = File("src/main/kotlin/com/example/veilark/engine/PrivilegedHelper.kt").readText()
    assertTrue(controller.contains("const val VERSION = \"6\""))
    assertTrue(helper.contains("failed to persist engine pid"))
    assertTrue(helper.contains("managed engine did not stop"))
    assertTrue(helper.contains("for _ in 0..<30"))
    assertFalse(helper.contains("try? String(pid).write"))
  }

  @Test
  fun packageBuildIsBoundToMacBuildNumber() {
    val build = File("build.gradle.kts").readText()
    val updater = File("updater/veilark-updater.swift").readText()
    assertTrue(build.contains("packageBuildVersion = macosBuild.toString()"))
    assertTrue(updater.contains("updateBuild == options.expectedBuild"))
    assertTrue(updater.contains("options.expectedBuild > currentBuild"))
  }

  @Test
  fun kotlinAndNativeUpdaterTrustTheSameDedicatedReleaseKey() {
    val properties = Properties().apply {
      File("gradle.properties").inputStream().use(::load)
    }
    val publicDer = Base64.getDecoder().decode(properties.getProperty("otaPublicKey"))
    val rawPublicKey = Base64.getEncoder().encodeToString(publicDer.takeLast(32).toByteArray())
    val updater = File("updater/veilark-updater.swift").readText()
    assertTrue(updater.contains("Data(base64Encoded: \"$rawPublicKey\")"))
    assertTrue(properties.getProperty("macosOtaManifestUrl").startsWith("https://"))
  }

  companion object {
    private val HEX_SHA256 = Regex("[0-9a-f]{64}")
  }
}
